package br.com.liberty.infrastructure.catalog;

import br.com.liberty.domain.tenancy.TenantScope;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Catálogo dos bancos dos tenants com cache: acertos duram o TTL longo (5 min), "não existe" dura
 * pouco (30 s) para um nome inventado não martelar o banco do cliente. Erro do SQL Server 207
 * (coluna inválida) ou 208 (objeto inválido) numa execução sinaliza que o schema mudou: {@link
 * #onDatabaseError} descarta o cache do tenant para a próxima requisição reler o catálogo.
 */
public final class TenantCatalog {

  private static final Logger LOG = LoggerFactory.getLogger(TenantCatalog.class);
  private static final int INVALID_COLUMN = 207;
  private static final int INVALID_OBJECT = 208;

  private record Key(TenantScope scope, TableRef reference) {}

  private final CatalogIntrospector introspector;
  private final Cache<Key, Optional<TableInfo>> tables;
  private final Cache<TenantScope, List<CatalogIntrospector.TableSummary>> lists;

  public TenantCatalog(CatalogIntrospector introspector, Duration hitTtl, Duration missTtl) {
    this(introspector, hitTtl, missTtl, Ticker.systemTicker());
  }

  TenantCatalog(
      CatalogIntrospector introspector, Duration hitTtl, Duration missTtl, Ticker ticker) {
    this.introspector = introspector;
    this.tables =
        Caffeine.newBuilder()
            .ticker(ticker)
            .maximumSize(50_000)
            .expireAfter(
                new Expiry<Key, Optional<TableInfo>>() {
                  @Override
                  public long expireAfterCreate(
                      Key key, Optional<TableInfo> value, long currentTime) {
                    return (value.isPresent() ? hitTtl : missTtl).toNanos();
                  }

                  @Override
                  public long expireAfterUpdate(
                      Key key, Optional<TableInfo> value, long currentTime, long currentDuration) {
                    return expireAfterCreate(key, value, currentTime);
                  }

                  @Override
                  public long expireAfterRead(
                      Key key, Optional<TableInfo> value, long currentTime, long currentDuration) {
                    return currentDuration;
                  }
                })
            .build();
    this.lists =
        Caffeine.newBuilder().ticker(ticker).expireAfterWrite(hitTtl).maximumSize(10_000).build();
  }

  /**
   * A tabela ou view do tenant, com as colunas do catálogo.
   *
   * @throws InvalidIdentifierException se a referência não for um identificador simples
   * @throws UnknownDatabaseObjectException se não existir ou não for visível ao usuário do tenant
   */
  public TableInfo table(TenantScope scope, String reference) {
    TableRef parsed = TableRef.parse(reference);
    Optional<TableInfo> found =
        tables.get(
            new Key(scope, parsed.normalized()), key -> introspector.introspect(scope, parsed));
    return found.orElseThrow(
        () ->
            new UnknownDatabaseObjectException(
                "Tabela inexistente: " + SqlIdentifiers.forMessage(parsed.name())));
  }

  /** Tabelas e views visíveis, para o editor de mapeamento (autocompletar). */
  public List<CatalogIntrospector.TableSummary> listTables(TenantScope scope) {
    return lists.get(scope, introspector::listTables);
  }

  /** Descarta o que se sabe do tenant (mudança de conexão, schema alterado). */
  public void invalidate(TenantScope scope) {
    tables.asMap().keySet().removeIf(key -> key.scope().equals(scope));
    lists.invalidate(scope);
  }

  /**
   * Se o erro indica schema alterado (207/208 em qualquer ponto da cadeia), recarrega o catálogo do
   * tenant.
   */
  public boolean onDatabaseError(TenantScope scope, Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t instanceof SQLException sql
          && (sql.getErrorCode() == INVALID_COLUMN || sql.getErrorCode() == INVALID_OBJECT)) {
        LOG.warn(
            "Schema do tenant {} mudou (erro {}); recarregando o catálogo",
            scope,
            sql.getErrorCode());
        invalidate(scope);
        return true;
      }
      if (t.getCause() == t) {
        break;
      }
    }
    return false;
  }
}
