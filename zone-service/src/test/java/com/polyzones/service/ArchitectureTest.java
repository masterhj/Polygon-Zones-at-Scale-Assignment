package com.polyzones.service;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import java.util.UUID;

class ArchitectureTest {
    private final com.tngtech.archunit.core.domain.JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.polyzones.service");

    @Test void webDoesNotDependOnPersistenceOrJpa() {
        ArchRule rule = noClasses().that().resideInAPackage("..web..")
                .should().dependOnClassesThat().resideInAnyPackage("..persistence..", "jakarta.persistence..", "com.polyzones.core.shape..");
        rule.check(classes);
    }

    @Test void applicationDoesNotDependOnHttpTransportTypes() {
        noClasses().that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage("jakarta.servlet..", "org.springframework.http..")
                .check(classes);
    }

    @Test void persistenceRepositoryMethodsAreTenantScoped() {
        for (var type : classes) {
            if (!type.getPackageName().contains("persistence.repository") || !type.isInterface()) continue;
            if (type.getSimpleName().equals("TenantJpaRepository")) continue;
            for (var method : type.getMethods()) {
                assertThat(method.getName()).as("repository method %s").startsWith("findByTenantId");
                assertThat(method.getRawParameterTypes().getFirst().isEquivalentTo(UUID.class))
                        .as("repository method %s tenantId type", method.getFullName()).isTrue();
            }
        }
    }
}
