package br.com.liberty.infrastructure.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.liberty.domain.catalog.InvalidIdentifierException;
import br.com.liberty.domain.catalog.TableRef;
import br.com.liberty.domain.tenancy.EnvironmentKind;
import br.com.liberty.domain.tenancy.TenantId;
import br.com.liberty.domain.tenancy.TenantScope;
import com.github.benmanes.caffeine.cache.Ticker;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TenantCatalogTest {

  private static final TenantScope A =
      new TenantScope(new TenantId("cliente-a"), EnvironmentKind.HML);
  private static final TenantScope B =
      new TenantScope(new TenantId("cliente-b"), EnvironmentKind.HML);

  private static final class FakeIntrospector implements CatalogIntrospector {
    final AtomicInteger introspections = new AtomicInteger();
    final AtomicInteger listings = new AtomicInteger();
    volatile boolean exists = true;

    @Override
    public Optional<TableInfo> introspect(TenantScope scope, TableRef reference) {
      introspections.incrementAndGet();
      if (!exists || !reference.name().equalsIgnoreCase("pedido")) {
        return Optional.empty();
      }
      ColumnInfo id =
          new ColumnInfo("id", "int", ColumnInfo.Kind.INTEGER, 10, 0, false, false, true, 1, 1);
      return Optional.of(
          new TableInfo(reference.schema(), "pedido", TableInfo.Kind.TABLE, List.of(id)));
    }

    @Override
    public List<TableSummary> listTables(TenantScope scope) {
      listings.incrementAndGet();
      return List.of(new TableSummary("dbo", "pedido", TableInfo.Kind.TABLE));
    }
  }

  private final FakeIntrospector introspector = new FakeIntrospector();
  private final AtomicLong clock = new AtomicLong();
  private final Ticker ticker = clock::get;
  private final TenantCatalog catalog =
      new TenantCatalog(introspector, Duration.ofMinutes(5), Duration.ofSeconds(30), ticker);

  private void advance(Duration duration) {
    clock.addAndGet(duration.toNanos());
  }

  @Test
  void hitsAreCachedForTheLongTtl() {
    catalog.table(A, "pedido");
    catalog.table(A, "PEDIDO");
    catalog.table(A, "dbo.pedido");

    assertThat(introspector.introspections.get()).isEqualTo(1);

    advance(Duration.ofMinutes(4));
    catalog.table(A, "pedido");
    assertThat(introspector.introspections.get()).isEqualTo(1);

    advance(Duration.ofMinutes(2));
    catalog.table(A, "pedido");
    assertThat(introspector.introspections.get()).isEqualTo(2);
  }

  @Test
  void missesAreCachedBrieflySoInventedNamesDoNotHammerTheTenantDatabase() {
    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(() -> catalog.table(A, "inventada"))
          .isInstanceOf(UnknownDatabaseObjectException.class);
    }
    assertThat(introspector.introspections.get()).isEqualTo(1);

    advance(Duration.ofSeconds(31));
    assertThatThrownBy(() -> catalog.table(A, "inventada"))
        .isInstanceOf(UnknownDatabaseObjectException.class);
    assertThat(introspector.introspections.get()).isEqualTo(2);
  }

  @Test
  void aTableCreatedLaterAppearsAfterTheMissTtl() {
    introspector.exists = false;
    assertThatThrownBy(() -> catalog.table(A, "pedido"))
        .isInstanceOf(UnknownDatabaseObjectException.class);
    introspector.exists = true;

    advance(Duration.ofSeconds(31));

    assertThat(catalog.table(A, "pedido").name()).isEqualTo("pedido");
  }

  @Test
  void invalidReferencesNeverReachTheIntrospector() {
    for (String bad : new String[] {"x]; DROP", "a.b.c", "", null, "tb-x"}) {
      assertThatThrownBy(() -> catalog.table(A, bad))
          .isInstanceOf(InvalidIdentifierException.class);
    }
    assertThat(introspector.introspections.get()).isZero();
  }

  @Test
  void tenantsDoNotShareCacheEntries() {
    catalog.table(A, "pedido");
    catalog.table(B, "pedido");

    assertThat(introspector.introspections.get()).isEqualTo(2);
  }

  @Test
  void errorsMessageNeverEchoesUnsafeInputButKeepsPlainNames() {
    assertThatThrownBy(() -> catalog.table(A, "inventada")).hasMessageContaining("inventada");
  }

  @Test
  void invalidObjectAndInvalidColumnErrorsReloadTheTenantCatalog() {
    catalog.table(A, "pedido");
    catalog.table(B, "pedido");
    assertThat(introspector.introspections.get()).isEqualTo(2);

    SQLException invalidObject =
        new SQLException("Nome de objeto 'pedido' inválido.", "S0002", 208);
    boolean reloaded =
        catalog.onDatabaseError(
            A, new RuntimeException("falha", new IllegalStateException(invalidObject)));
    catalog.table(A, "pedido");
    catalog.table(B, "pedido");

    assertThat(reloaded).isTrue();
    assertThat(introspector.introspections.get()).as("só o tenant A relê").isEqualTo(3);
    assertThat(catalog.onDatabaseError(B, new SQLException("coluna", "S0001", 207))).isTrue();
  }

  @Test
  void otherErrorsDoNotTouchTheCache() {
    catalog.table(A, "pedido");

    assertThat(catalog.onDatabaseError(A, new SQLException("duplicado", "23000", 2627))).isFalse();
    assertThat(catalog.onDatabaseError(A, new RuntimeException("x"))).isFalse();
    assertThat(catalog.onDatabaseError(A, new SQLException("sem código", "S0001", 0))).isFalse();
    catalog.table(A, "pedido");

    assertThat(introspector.introspections.get()).isEqualTo(1);
  }

  @Test
  void tableListsAreCachedAndInvalidatedWithTheTenant() {
    catalog.listTables(A);
    catalog.listTables(A);
    assertThat(introspector.listings.get()).isEqualTo(1);

    catalog.invalidate(A);
    catalog.listTables(A);

    assertThat(introspector.listings.get()).isEqualTo(2);
  }
}
