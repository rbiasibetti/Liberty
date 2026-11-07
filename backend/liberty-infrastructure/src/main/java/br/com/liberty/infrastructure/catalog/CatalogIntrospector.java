package br.com.liberty.infrastructure.catalog;

import br.com.liberty.domain.catalog.TableRef;
import br.com.liberty.domain.tenancy.TenantScope;
import java.util.List;
import java.util.Optional;

/**
 * Lê o catálogo do banco de um tenant. Implementações não guardam cache: quem cuida disso é o
 * {@link TenantCatalog}.
 */
public interface CatalogIntrospector {

  /** Resumo de uma tabela ou view, para listas e autocompletar. */
  record TableSummary(String schema, String name, TableInfo.Kind kind) {}

  /** A tabela ou view descrita, ou vazio se não existir ou o usuário do tenant não enxergar. */
  Optional<TableInfo> introspect(TenantScope scope, TableRef reference);

  /** Tabelas e views visíveis ao usuário do tenant, sem esquemas do sistema, ordenadas. */
  List<TableSummary> listTables(TenantScope scope);
}
