package br.com.liberty.infrastructure.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.liberty.domain.catalog.InvalidIdentifierException;
import br.com.liberty.domain.catalog.TableRef;
import br.com.liberty.domain.tenancy.EnvironmentKind;
import br.com.liberty.domain.tenancy.TenantId;
import br.com.liberty.domain.tenancy.TenantScope;
import br.com.liberty.infrastructure.tenancy.TenantConnectionInfo;
import br.com.liberty.infrastructure.tenancy.TenantPoolRegistry;
import br.com.liberty.infrastructure.testsupport.CoreTestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Introspecção contra SQL Server real, inclusive com um usuário que só enxerga parte do banco. */
class JdbcCatalogIntrospectorTest {

  private static final TenantScope FULL =
      new TenantScope(new TenantId("completo"), EnvironmentKind.HML);
  private static final TenantScope LIMITED =
      new TenantScope(new TenantId("limitado"), EnvironmentKind.HML);

  private static CoreTestDatabase.Scratch db;
  private static String limitedUser;
  private static final String LIMITED_PASSWORD = "Lim-" + UUID.randomUUID() + "-aA1!";
  private static TenantPoolRegistry registry;
  private static JdbcCatalogIntrospector introspector;
  private static TenantCatalog catalog;

  private static void exec(String... statements) throws SQLException {
    try (Connection c = DriverManager.getConnection(db.url(), db.user(), db.password());
        Statement st = c.createStatement()) {
      for (String sql : statements) {
        st.execute(sql);
      }
    }
  }

  @BeforeAll
  static void createSchema() throws SQLException {
    db = CoreTestDatabase.create();
    limitedUser = "cat_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    exec(
        "CREATE SCHEMA vendas",
        "CREATE TABLE dbo.tb_cliente (id INT IDENTITY PRIMARY KEY, nr_documento VARCHAR(14) NOT NULL,"
            + " nm_cliente NVARCHAR(200) NOT NULL, dt_criacao DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(),"
            + " total_calc AS (id * 2), versao ROWVERSION, preco MONEY NULL, chave UNIQUEIDENTIFIER NULL,"
            + " ativo BIT NOT NULL DEFAULT 1, nasc DATE NULL, hora TIME NULL, fuso DATETIMEOFFSET NULL,"
            + " valor DECIMAL(18,4) NULL, ratio FLOAT NULL, bin VARBINARY(10) NULL, doc XML NULL)",
        "CREATE TABLE dbo.tb_pedido (id BIGINT IDENTITY PRIMARY KEY, cliente_id INT NOT NULL REFERENCES dbo.tb_cliente(id),"
            + " vl_total DECIMAL(18,2) NOT NULL)",
        "CREATE TABLE dbo.tb_pedido_item (pedido_id BIGINT NOT NULL, item_no INT NOT NULL, cd_produto VARCHAR(30) NOT NULL,"
            + " PRIMARY KEY (pedido_id, item_no))",
        "CREATE VIEW dbo.vw_pedidos AS SELECT id, vl_total FROM dbo.tb_pedido",
        "CREATE TABLE vendas.produto (sku VARCHAR(30) PRIMARY KEY, preco DECIMAL(18,4) NOT NULL)",
        "CREATE TABLE dbo.ped_ido (id INT PRIMARY KEY)",
        "CREATE TABLE dbo.pedXido (id INT PRIMARY KEY, outra INT)",
        "CREATE TABLE dbo.[Order Details] (id INT PRIMARY KEY)",
        "CREATE TABLE dbo.[tb-hifen] (id INT PRIMARY KEY)",
        "CREATE TABLE dbo.secreta (id INT)",
        "CREATE LOGIN "
            + limitedUser
            + " WITH PASSWORD = '"
            + LIMITED_PASSWORD
            + "', CHECK_POLICY = OFF",
        "CREATE USER " + limitedUser + " FOR LOGIN " + limitedUser,
        "GRANT SELECT, INSERT, UPDATE ON dbo.tb_cliente TO " + limitedUser,
        "GRANT SELECT ON dbo.vw_pedidos TO " + limitedUser);
    registry =
        new TenantPoolRegistry(
            scope ->
                Optional.of(
                    new TenantConnectionInfo(
                        db.url(),
                        scope.equals(FULL) ? db.user() : limitedUser,
                        "ref/" + scope.tenant(),
                        2)),
            reference -> reference.endsWith("limitado") ? LIMITED_PASSWORD : db.password(),
            Duration.ofMinutes(10));
    introspector = new JdbcCatalogIntrospector(registry);
    catalog = new TenantCatalog(introspector, Duration.ofMinutes(5), Duration.ofSeconds(30));
  }

  @AfterAll
  static void cleanUp() throws SQLException {
    registry.destroy();
    exec("DROP USER IF EXISTS " + limitedUser, "DROP LOGIN " + limitedUser);
  }

  private static TableInfo info(TenantScope scope, String reference) {
    return introspector.introspect(scope, TableRef.parse(reference)).orElseThrow();
  }

  @Test
  void describesColumnsWithTypesKeysDefaultsAndGeneratedFlags() {
    TableInfo cliente = info(FULL, "tb_cliente");

    assertThat(cliente.kind()).isEqualTo(TableInfo.Kind.TABLE);
    assertThat(cliente.writable()).isTrue();
    assertThat(cliente.columns())
        .extracting(ColumnInfo::name)
        .startsWith("id", "nr_documento", "nm_cliente", "dt_criacao", "total_calc", "versao");
    ColumnInfo id = cliente.column("id").orElseThrow();
    assertThat(id.primaryKey()).isTrue();
    assertThat(id.generated()).as("identidade").isTrue();
    assertThat(id.writable()).isFalse();
    assertThat(id.kind()).isEqualTo(ColumnInfo.Kind.INTEGER);
    ColumnInfo doc = cliente.column("nr_documento").orElseThrow();
    assertThat(doc.kind()).isEqualTo(ColumnInfo.Kind.STRING);
    assertThat(doc.size()).isEqualTo(14);
    assertThat(doc.nullable()).isFalse();
    assertThat(doc.required()).isTrue();
    ColumnInfo criacao = cliente.column("dt_criacao").orElseThrow();
    assertThat(criacao.hasDefault()).isTrue();
    assertThat(criacao.required()).as("tem valor padrão").isFalse();
    assertThat(criacao.kind()).isEqualTo(ColumnInfo.Kind.DATE_TIME);
    assertThat(cliente.column("total_calc").orElseThrow().generated())
        .as("coluna calculada")
        .isTrue();
    assertThat(cliente.column("versao").orElseThrow().generated()).as("rowversion").isTrue();
    assertThat(cliente.column("preco").orElseThrow().kind()).isEqualTo(ColumnInfo.Kind.DECIMAL);
    assertThat(cliente.column("chave").orElseThrow().kind()).isEqualTo(ColumnInfo.Kind.UUID);
    assertThat(cliente.column("ativo").orElseThrow().kind()).isEqualTo(ColumnInfo.Kind.BOOLEAN);
    assertThat(cliente.column("nasc").orElseThrow().kind()).isEqualTo(ColumnInfo.Kind.DATE);
    assertThat(cliente.column("hora").orElseThrow().kind()).isEqualTo(ColumnInfo.Kind.TIME);
    assertThat(cliente.column("fuso").orElseThrow().kind())
        .isEqualTo(ColumnInfo.Kind.DATE_TIME_OFFSET);
    assertThat(cliente.column("valor").orElseThrow().scale()).isEqualTo(4);
    assertThat(cliente.column("ratio").orElseThrow().kind()).isEqualTo(ColumnInfo.Kind.FLOAT);
    assertThat(cliente.column("bin").orElseThrow().kind()).isEqualTo(ColumnInfo.Kind.BINARY);
    assertThat(cliente.column("doc").orElseThrow().kind()).isEqualTo(ColumnInfo.Kind.XML);
  }

  @Test
  void reportsCompositePrimaryKeysInOrder() {
    assertThat(info(FULL, "tb_pedido_item").primaryKey())
        .extracting(ColumnInfo::name)
        .containsExactly("pedido_id", "item_no");
  }

  @Test
  void viewsAreReadOnly() {
    TableInfo view = info(FULL, "vw_pedidos");

    assertThat(view.kind()).isEqualTo(TableInfo.Kind.VIEW);
    assertThat(view.writable()).isFalse();
  }

  @Test
  void resolvesSchemasExplicitlyAndDefaultsToDbo() {
    assertThat(info(FULL, "vendas.produto").schema()).isEqualTo("vendas");
    assertThat(introspector.introspect(FULL, TableRef.parse("produto")))
        .as("produto não está em dbo")
        .isEmpty();
    assertThat(SqlIdentifiers.table(info(FULL, "vendas.produto"))).isEqualTo("[vendas].[produto]");
  }

  @Test
  void likeWildcardsNeverMatchOtherTables() {
    TableInfo exact = info(FULL, "ped_ido");

    assertThat(exact.name()).isEqualTo("ped_ido");
    assertThat(exact.columns()).extracting(ColumnInfo::name).containsExactly("id");
    assertThat(info(FULL, "pedXido").columns())
        .extracting(ColumnInfo::name)
        .containsExactly("id", "outra");
    assertThat(introspector.introspect(FULL, new TableRef("dbo", "ped_"))).isEmpty();
    assertThat(introspector.introspect(FULL, new TableRef("dbo", "ped%"))).isEmpty();
    assertThat(introspector.introspect(FULL, new TableRef("dbo", "%"))).isEmpty();
    assertThat(introspector.introspect(FULL, new TableRef("%", "tb_cliente"))).isEmpty();
    assertThat(introspector.introspect(FULL, new TableRef("dbo", "[a-z]b_cliente"))).isEmpty();
  }

  @Test
  void caseInsensitiveReferencesResolveToTheCatalogSpelling() {
    TableInfo table = info(FULL, "TB_CLIENTE");

    assertThat(table.name()).isEqualTo("tb_cliente");
    assertThat(SqlIdentifiers.table(table)).isEqualTo("[dbo].[tb_cliente]");
    assertThat(SqlIdentifiers.column(table, "NM_CLIENTE")).isEqualTo("[nm_cliente]");
  }

  @Test
  void systemSchemasAndSystemObjectsAreNeverExposed() {
    for (String reference :
        new String[] {
          "sys.objects",
          "sys.databases",
          "INFORMATION_SCHEMA.TABLES",
          "information_schema.columns",
          "guest.x",
          "db_owner.x"
        }) {
      assertThat(introspector.introspect(FULL, TableRef.parse(reference))).as(reference).isEmpty();
    }
    assertThat(introspector.listTables(FULL))
        .noneMatch(
            t ->
                t.schema().equalsIgnoreCase("sys")
                    || t.schema().equalsIgnoreCase("INFORMATION_SCHEMA"));
  }

  @Test
  void listsOnlyUsableNamesSortedWithKinds() {
    List<CatalogIntrospector.TableSummary> tables = introspector.listTables(FULL);

    assertThat(tables)
        .extracting(t -> t.schema() + "." + t.name())
        .contains("dbo.tb_cliente", "dbo.tb_pedido", "dbo.vw_pedidos", "vendas.produto")
        .doesNotContain("dbo.Order Details", "dbo.tb-hifen")
        .isSorted();
    assertThat(tables)
        .filteredOn(t -> t.name().equals("vw_pedidos"))
        .extracting(CatalogIntrospector.TableSummary::kind)
        .containsExactly(TableInfo.Kind.VIEW);
  }

  @Test
  void namesThatCannotBeReferencedAreRefusedEvenThoughTheyExist() {
    assertThatThrownBy(() -> catalog.table(FULL, "[Order Details]"))
        .isInstanceOf(InvalidIdentifierException.class);
    assertThatThrownBy(() -> catalog.table(FULL, "Order Details"))
        .isInstanceOf(InvalidIdentifierException.class);
    assertThatThrownBy(() -> catalog.table(FULL, "tb-hifen"))
        .isInstanceOf(InvalidIdentifierException.class);
  }

  @Test
  void maliciousReferencesNeverTouchTheDatabaseAndTheTablesSurvive() throws SQLException {
    for (String attack :
        new String[] {
          "tb_cliente; DROP TABLE tb_cliente;--",
          "x]; DROP TABLE dbo.tb_cliente;--",
          "tb_cliente' OR '1'='1",
          "master..sysdatabases",
          "tb_cliente--"
        }) {
      assertThatThrownBy(() -> catalog.table(FULL, attack))
          .isInstanceOf(InvalidIdentifierException.class);
    }
    assertThatThrownBy(() -> catalog.table(FULL, "nao_existe"))
        .isInstanceOf(UnknownDatabaseObjectException.class);
    assertThat(info(FULL, "tb_cliente").columns()).isNotEmpty();
    TableInfo cliente = info(FULL, "tb_cliente");
    assertThatThrownBy(() -> SqlIdentifiers.column(cliente, "id); DROP TABLE dbo.tb_cliente;--"))
        .isInstanceOf(UnknownDatabaseObjectException.class);
  }

  @Test
  void aLimitedUserOnlySeesWhatItIsAllowedToSee() {
    assertThat(introspector.introspect(LIMITED, TableRef.parse("tb_cliente"))).isPresent();
    assertThat(introspector.introspect(LIMITED, TableRef.parse("vw_pedidos"))).isPresent();
    for (String hidden :
        new String[] {"tb_pedido", "secreta", "vendas.produto", "tb_pedido_item"}) {
      assertThat(introspector.introspect(LIMITED, TableRef.parse(hidden))).as(hidden).isEmpty();
    }
    assertThat(introspector.listTables(LIMITED))
        .extracting(CatalogIntrospector.TableSummary::name)
        .containsExactlyInAnyOrder("tb_cliente", "vw_pedidos");
    assertThatThrownBy(() -> catalog.table(LIMITED, "secreta"))
        .isInstanceOf(UnknownDatabaseObjectException.class);
  }

  @Test
  void aStaleCacheHealsAfterAnInvalidObjectError() throws SQLException {
    exec("CREATE TABLE dbo.temporaria (id INT PRIMARY KEY)");
    TenantCatalog local =
        new TenantCatalog(introspector, Duration.ofMinutes(5), Duration.ofSeconds(30));
    assertThat(local.table(FULL, "temporaria").name()).isEqualTo("temporaria");
    exec("DROP TABLE dbo.temporaria");
    assertThat(local.table(FULL, "temporaria").name())
        .as("cache velho ainda responde")
        .isEqualTo("temporaria");

    SQLException error = null;
    try (Connection c = registry.pool(FULL).getConnection();
        Statement st = c.createStatement()) {
      st.execute("SELECT * FROM " + SqlIdentifiers.table(local.table(FULL, "temporaria")));
    } catch (SQLException e) {
      error = e;
    }

    assertThat((Object) error).isNotNull();
    assertThat(error.getErrorCode()).isEqualTo(208);
    assertThat(local.onDatabaseError(FULL, error)).isTrue();
    assertThatThrownBy(() -> local.table(FULL, "temporaria"))
        .isInstanceOf(UnknownDatabaseObjectException.class);
  }
}
