package com.skbingegalaxy.distribution.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Cluster-safe scheduling, added together with the first scheduled job — as the note on
 * {@code DistributionServiceApplication} said it must be.
 *
 * <p>{@code usingDbTime()} matters more than it looks: without it each replica compares
 * lock expiry against its OWN clock, so a few seconds of drift lets two nodes both
 * believe the lock is free. Using the database clock gives every contender one shared
 * source of time, which is the only way the lock means anything.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }
}
