package com.skbingegalaxy.booking.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
@Slf4j
public class CustomerPricingProfileSchemaMigration implements ApplicationRunner {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!isPostgres()) {
            log.debug("Skipping customer pricing schema migration for non-PostgreSQL database");
            return;
        }

        dropLegacyCustomerOnlyUniqueConstraints();
        ensureScopedUniqueIndexes();
    }

    private boolean isPostgres() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            return "PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName());
        }
    }

    private void dropLegacyCustomerOnlyUniqueConstraints() {
        List<String> constraints = jdbcTemplate.queryForList("""
            SELECT tc.constraint_name
            FROM information_schema.table_constraints tc
            JOIN information_schema.constraint_column_usage ccu
              ON tc.constraint_name = ccu.constraint_name
             AND tc.table_schema = ccu.table_schema
            WHERE tc.table_schema = current_schema()
              AND tc.table_name = 'customer_pricing_profiles'
              AND tc.constraint_type = 'UNIQUE'
            GROUP BY tc.constraint_name
            HAVING COUNT(*) = 1 AND MAX(ccu.column_name) = 'customer_id'
            """, String.class);

        for (String constraint : constraints) {
            if (!SAFE_IDENTIFIER.matcher(constraint).matches()) {
                log.warn("Skipping unexpected constraint identifier on customer_pricing_profiles: {}", constraint);
                continue;
            }

            jdbcTemplate.execute("ALTER TABLE customer_pricing_profiles DROP CONSTRAINT IF EXISTS \"" + constraint + "\"");
            log.info("Dropped legacy customer_pricing_profiles unique constraint {}", constraint);
        }
    }

    private void ensureScopedUniqueIndexes() {
        jdbcTemplate.execute("""
            CREATE UNIQUE INDEX IF NOT EXISTS ux_customer_pricing_profiles_customer_global
            ON customer_pricing_profiles (customer_id)
            WHERE binge_id IS NULL
            """);

        jdbcTemplate.execute("""
            CREATE UNIQUE INDEX IF NOT EXISTS ux_customer_pricing_profiles_customer_binge
            ON customer_pricing_profiles (customer_id, binge_id)
            WHERE binge_id IS NOT NULL
            """);

        log.info("Ensured scoped customer pricing indexes exist");
    }
}