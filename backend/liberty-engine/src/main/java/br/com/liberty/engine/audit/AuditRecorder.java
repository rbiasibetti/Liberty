package br.com.liberty.engine.audit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Gravação assíncrona da auditoria. A requisição só entrega o evento a uma fila limitada, sem
 * esperar: se a fila está cheia (o banco central não acompanha), o evento é descartado e contado
 * ({@code liberty.audit.dropped}) em vez de atrasar a resposta. Uma thread própria grava em lotes.
 * Falha de gravação perde o lote e é contada ({@code liberty.audit.failed}); nada disso chega ao
 * cliente da API.
 */
public final class AuditRecorder implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(AuditRecorder.class);

  private static final String INSERT =
      "INSERT INTO int_audit_execution (created_at, tenant_id, integration_key, integration_id, version_id,"
          + " environment, http_method, route, status_code, duration_ms, db_duration_ms, script_duration_ms,"
          + " error_code, error_message, request_id, client_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

  private final JdbcTemplate jdbc;
  private final BlockingQueue<AuditEvent> queue;
  private final int batchSize;
  private final Duration flushInterval;
  private final Cache<String, Optional<Long>> tenantIds =
      Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(Duration.ofMinutes(5)).build();
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong written = new AtomicLong();
  private final AtomicLong failed = new AtomicLong();
  private final Counter droppedCounter;
  private final Counter writtenCounter;
  private final Counter failedCounter;
  private final Timer batchTimer;
  private final Thread writer;
  private volatile boolean running = true;

  public AuditRecorder(
      JdbcTemplate jdbc,
      MeterRegistry meters,
      int capacity,
      int batchSize,
      Duration flushInterval) {
    this.jdbc = jdbc;
    this.queue = new ArrayBlockingQueue<>(capacity);
    this.batchSize = batchSize;
    this.flushInterval = flushInterval;
    this.droppedCounter =
        Counter.builder("liberty.audit.dropped")
            .description("Eventos descartados com a fila cheia")
            .register(meters);
    this.writtenCounter = Counter.builder("liberty.audit.written").register(meters);
    this.failedCounter =
        Counter.builder("liberty.audit.failed")
            .description("Eventos perdidos por falha de gravação")
            .register(meters);
    this.batchTimer = Timer.builder("liberty.audit.batch.duration").register(meters);
    Gauge.builder("liberty.audit.queue.size", queue, BlockingQueue::size).register(meters);
    Gauge.builder("liberty.audit.queue.capacity", queue, q -> q.size() + q.remainingCapacity())
        .register(meters);
    this.writer = new Thread(this::loop, "audit-writer");
    this.writer.setDaemon(true);
    this.writer.start();
  }

  /** Nunca bloqueia nem lança; devolve se o evento entrou na fila. */
  public boolean record(AuditEvent event) {
    if (running && queue.offer(event)) {
      return true;
    }
    dropped.incrementAndGet();
    droppedCounter.increment();
    return false;
  }

  public long dropped() {
    return dropped.get();
  }

  public long written() {
    return written.get();
  }

  public long failed() {
    return failed.get();
  }

  public int queued() {
    return queue.size();
  }

  private void loop() {
    List<AuditEvent> batch = new ArrayList<>(batchSize);
    while (running || !queue.isEmpty()) {
      try {
        AuditEvent first = queue.poll(flushInterval.toMillis(), TimeUnit.MILLISECONDS);
        if (first == null) {
          continue;
        }
        batch.add(first);
        queue.drainTo(batch, batchSize - 1);
        flush(batch);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } finally {
        batch.clear();
      }
    }
  }

  private void flush(List<AuditEvent> batch) {
    long start = System.nanoTime();
    try {
      List<AuditEvent> rows = new ArrayList<>(batch.size());
      List<Long> ids = new ArrayList<>(batch.size());
      for (AuditEvent event : batch) {
        Long id = tenantId(event.tenantSlug());
        if (id != null) {
          rows.add(event);
          ids.add(id);
        } else {
          loss(1);
        }
      }
      if (rows.isEmpty()) {
        return;
      }
      jdbc.batchUpdate(
          INSERT,
          new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
              fill(ps, ids.get(i), rows.get(i));
            }

            @Override
            public int getBatchSize() {
              return rows.size();
            }
          });
      written.addAndGet(rows.size());
      writtenCounter.increment(rows.size());
    } catch (RuntimeException e) {
      LOG.error("Falha ao gravar {} eventos de auditoria", batch.size(), e);
      loss(batch.size());
    } finally {
      batchTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
    }
  }

  private void loss(int count) {
    failed.addAndGet(count);
    failedCounter.increment(count);
  }

  private Long tenantId(String slug) {
    return tenantIds
        .get(
            slug,
            s -> {
              var found =
                  jdbc.queryForList("SELECT id FROM int_tenant WHERE slug = ?", Long.class, s);
              return found.isEmpty() ? Optional.<Long>empty() : Optional.of(found.getFirst());
            })
        .orElse(null);
  }

  private static void fill(PreparedStatement ps, long tenantId, AuditEvent e) throws SQLException {
    ps.setTimestamp(1, Timestamp.from(e.createdAt()));
    ps.setLong(2, tenantId);
    ps.setString(3, e.integrationKey());
    nullableLong(ps, 4, e.integrationId());
    nullableLong(ps, 5, e.versionId());
    ps.setString(6, e.environment());
    ps.setString(7, e.httpMethod());
    ps.setString(8, truncate(e.route(), 400));
    ps.setShort(9, (short) e.statusCode());
    ps.setInt(10, (int) Math.min(e.durationMs(), Integer.MAX_VALUE));
    nullableInt(ps, 11, e.dbDurationMs());
    nullableInt(ps, 12, e.scriptDurationMs());
    ps.setString(13, truncate(e.errorCode(), 50));
    ps.setString(14, truncate(AuditMasker.mask(e.errorMessage()), 1000));
    ps.setString(15, truncate(e.requestId(), 64));
    ps.setString(16, truncate(e.clientId(), 100));
  }

  private static void nullableLong(PreparedStatement ps, int index, Long value)
      throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.BIGINT);
    } else {
      ps.setLong(index, value);
    }
  }

  private static void nullableInt(PreparedStatement ps, int index, Long value) throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.INTEGER);
    } else {
      ps.setInt(index, (int) Math.min(value, Integer.MAX_VALUE));
    }
  }

  private static String truncate(String text, int max) {
    return text == null || text.length() <= max ? text : text.substring(0, max);
  }

  /** Para o aceite de novos eventos e grava o que está na fila (com prazo). */
  @Override
  public void close() {
    running = false;
    try {
      writer.join(10_000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    writer.interrupt();
  }
}
