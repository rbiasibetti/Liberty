package br.com.liberty.infrastructure.persistence;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class RepositoryRulesTest {

  private final JavaClasses classes =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages("br.com.liberty.infrastructure.persistence");

  /** Repositórios genéricos expõem `findById` e `findAll` sem tenant, abrindo caminho para IDOR. */
  @Test
  void repositoriesDoNotExposeUnscopedCrudMethods() {
    noClasses()
        .that()
        .haveSimpleNameEndingWith("Repository")
        .should()
        .beAssignableTo(org.springframework.data.repository.CrudRepository.class)
        .orShould()
        .beAssignableTo(org.springframework.data.jpa.repository.JpaRepository.class)
        .check(classes);
  }
}
