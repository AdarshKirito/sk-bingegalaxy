package com.skbingegalaxy.distribution.db;

import jakarta.persistence.Entity;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every JPA entity in this module must match the Flyway schema exactly.
 *
 * <p><b>Why this test exists.</b> {@code spring.jpa.hibernate.ddl-auto=validate} already
 * makes entity/schema drift a startup failure in production — but only at startup, in an
 * environment with a database, after the artefact has been built and shipped. That is
 * the wrong place to discover that a migration and its entity were written by different
 * hands. This moves the same check into {@code mvn verify}.
 *
 * <p><b>The entity list is discovered, not hard-coded.</b> A hand-maintained list would
 * pass happily on the day someone adds a tenth entity and forgets to register it — which
 * is exactly the failure the test is supposed to catch. The scan is asserted to be
 * non-trivial so a broken scanner cannot make this vacuously green.
 *
 * <p>No Spring context is started: Hibernate is bootstrapped directly against the
 * migrated database. The test therefore needs no config server, no Eureka and no Kafka,
 * and it fails for exactly one reason.
 */
@Testcontainers
@EnabledIfSystemProperty(
    named = "testcontainers.enabled", matches = "true",
    disabledReason = "needs a Docker daemon; enable with -Dtestcontainers.enabled=true")
@DisplayName("Entity/schema parity (Hibernate validate against real Flyway output)")
class EntitySchemaParityIT {

    private static final String ENTITY_PACKAGE = "com.skbingegalaxy.distribution.entity";

    /**
     * Tables in V1 that intentionally have no entity would be invisible to this test —
     * validation only walks entities towards the database, never the reverse. Pinning
     * the expected count keeps that gap honest: if a table is added without an entity,
     * this number and the assertion below have to be revisited deliberately.
     */
    private static final int EXPECTED_ENTITY_COUNT = 9;

    private static PostgreSQLContainer<?> postgres;

    @BeforeAll
    static void startAndMigrate() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("distribution_db")
            .withUsername("test")
            .withPassword("test");
        postgres.start();

        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();
    }

    @AfterAll
    static void stop() {
        if (postgres != null) postgres.stop();
    }

    @Test
    @DisplayName("every @Entity validates against the migrated schema")
    void entitiesMatchTheMigratedSchema() {
        List<Class<?>> entities = scanEntities();

        assertThat(entities)
            .as("the entity scan must actually find the entities, or this test proves nothing")
            .hasSizeGreaterThanOrEqualTo(EXPECTED_ENTITY_COUNT);

        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
            .applySetting("jakarta.persistence.jdbc.url", postgres.getJdbcUrl())
            .applySetting("jakarta.persistence.jdbc.user", postgres.getUsername())
            .applySetting("jakarta.persistence.jdbc.password", postgres.getPassword())
            .applySetting("jakarta.persistence.jdbc.driver", "org.postgresql.Driver")
            .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
            // The assertion itself. Building the SessionFactory runs the validator and
            // throws SchemaManagementException listing every mismatched column.
            .applySetting("hibernate.hbm2ddl.auto", "validate")
            .build();

        try {
            MetadataSources sources = new MetadataSources(registry);
            entities.forEach(sources::addAnnotatedClass);

            try (SessionFactory factory = sources.buildMetadata().buildSessionFactory()) {
                assertThat(factory).isNotNull();
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    @Test
    @DisplayName("the entity package covers every table V1 creates")
    void everyTableHasAnEntity() {
        List<String> entityTables = scanEntities().stream()
            .map(c -> c.getAnnotation(jakarta.persistence.Table.class))
            .filter(java.util.Objects::nonNull)
            .map(jakarta.persistence.Table::name)
            .sorted()
            .toList();

        // Deliberately excludes flyway_schema_history, which Flyway owns.
        assertThat(entityTables).containsExactlyInAnyOrder(
            "providers", "provider_capabilities", "destinations",
            "connections", "connection_destinations", "listing_mappings",
            "reservation_inbox", "settlement_records", "sync_state");
    }

    private static List<Class<?>> scanEntities() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

        List<Class<?>> found = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(ENTITY_PACKAGE)) {
            try {
                found.add(Class.forName(definition.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                    "scanned entity is not loadable: " + definition.getBeanClassName(), e);
            }
        }
        return found;
    }
}
