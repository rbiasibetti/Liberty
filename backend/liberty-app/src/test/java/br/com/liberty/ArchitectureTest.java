package br.com.liberty;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

  private final JavaClasses classes =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages("br.com.liberty");

  @Test
  void domainHasNoFrameworkDependency() {
    noClasses()
        .that()
        .resideInAPackage("br.com.liberty.domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework..",
            "jakarta..",
            "javax..",
            "com.fasterxml..",
            "tools.jackson..",
            "br.com.liberty.application..",
            "br.com.liberty.infrastructure..")
        .allowEmptyShould(true)
        .check(classes);
  }

  @Test
  void applicationDoesNotDependOnInfrastructure() {
    noClasses()
        .that()
        .resideInAPackage("br.com.liberty.application..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("br.com.liberty.infrastructure..")
        .allowEmptyShould(true)
        .check(classes);
  }

  @Test
  void noPackageCycles() {
    slices().matching("br.com.liberty.(*)..").should().beFreeOfCycles().check(classes);
  }
}
