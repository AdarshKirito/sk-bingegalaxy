package com.skbingegalaxy.booking.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

@Component
@RequiredArgsConstructor
@Slf4j
public class BingeAboutSchemaMigration implements ApplicationRunner {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!isPostgres()) {
            log.debug("Skipping binge about schema migration for non-PostgreSQL database");
            return;
        }

        jdbcTemplate.execute("""
            ALTER TABLE binges
            ADD COLUMN IF NOT EXISTS customer_about_config_json TEXT
            """);

        log.info("Ensured binges.customer_about_config_json column exists");
    }

    private boolean isPostgres() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            return "PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName());
        }
    }
}