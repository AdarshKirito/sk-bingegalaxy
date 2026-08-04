package com.skbingegalaxy.booking.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Register item <b>TEST-01</b>: the room/venue occupancy backstop is a PostgreSQL
 * trigger, so no mocked unit test can prove it. This runs the <b>real Flyway
 * migration chain</b> against a real PostgreSQL 16 and then drives the trigger
 * directly with SQL.
 *
 * <p>Two things are under test, and both matter:
 * <ol>
 *   <li><b>The whole migration chain applies cleanly</b> from V1 to head. Ordering
 *       mistakes, a column referenced before it exists, a broken {@code CREATE OR
 *       REPLACE} — none of those are visible to a mocked test, and all of them are
 *       deploy-stopping. {@code ddl-auto=validate} makes a bad migration a startup
 *       failure in production.</li>
 *   <li><b>The trigger agrees with the application.</b> V81 widened conflict
 *       detection from billable intervals to occupancy windows
 *       ({@code [start − setup, start + duration + cleanup)}) in both Java and SQL.
 *       If the two ever drift, a narrower trigger means silent oversell and a wider
 *       one means commit-time rejection of writes the application already accepted.
 *       Both are outages, so the SQL side needs its own proof.</li>
 * </ol>
 *
 * <p><b>Skipped without Docker.</b> Guarded by {@code -Dtestcontainers.enabled=true}
 * so a contributor with no Docker daemon still gets a green {@code mvn test}
 * instead of a wall of connection errors. CI should set that property. Run locally:
 * <pre>mvn -pl booking-service test -Dtestcontainers.enabled=true</pre>
 */
@Testcontainers
@EnabledIfSystemProperty(
    named = "testcontainers.enabled", matches = "true",
    disabledReason = "needs a Docker daemon; enable with -Dtestcontainers.enabled=true")
@DisplayName("Occupancy backstop trigger (real PostgreSQL + real migrations)")
class OccupancyBackstopIT {

    private static PostgreSQLContainer<?> postgres;
    private static Connection connection;

    /** Every fixture books into this venue; room 1 is exclusive, room 2 holds two parties. */
    private static final long BINGE_ID = 1L;
    private static final long ROOM_EXCLUSIVE = 1L;
    private static final long ROOM_CAPACITY_2 = 2L;

    @BeforeAll
    static void startDatabaseAndMigrate() throws SQLException {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("booking_db")
            .withUsername("test")
            .withPassword("test");
        postgres.start();

        // The real chain, exactly as production applies it. A failure here is a
        // broken migration, which is the single most valuable thing this catches.
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();

        connection = DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

        seedVenue();
    }

    @AfterAll
    static void stop() throws SQLException {
        if (connection != null) connection.close();
        if (postgres != null) postgres.stop();
    }

    private static void seedVenue() throws SQLException {
        exec("INSERT INTO binges (id, name, admin_id, country, currency, timezone, status, active) "
            + "VALUES (" + BINGE_ID + ", 'Backstop Test Venue', 1, 'IN', 'INR', 'Asia/Kolkata', 'APPROVED', TRUE) "
            + "ON CONFLICT (id) DO NOTHING");
        exec("INSERT INTO venue_rooms (id, binge_id, name, room_type, capacity, active, status, sort_order) VALUES "
            + "(" + ROOM_EXCLUSIVE + ", " + BINGE_ID + ", 'Exclusive Room', 'PRIVATE_ROOM', 1, TRUE, 'APPROVED', 0), "
            + "(" + ROOM_CAPACITY_2 + ", " + BINGE_ID + ", 'Shared Room', 'MAIN_HALL', 2, TRUE, 'APPROVED', 1) "
            + "ON CONFLICT (id) DO NOTHING");
        exec("INSERT INTO event_types (id, binge_id, name, base_price, hourly_rate, price_per_guest, "
            + "min_hours, max_hours, active) "
            + "VALUES (1, " + BINGE_ID + ", 'Celebration', 1000, 500, 0, 1, 8, TRUE) "
            + "ON CONFLICT (id) DO NOTHING");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static void exec(String sql) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        }
    }

    private static long scalar(String sql) throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Insert a booking with explicit turnover buffers. Throws when the trigger rejects it. */
    private static void insertBooking(Long roomId, LocalDate date, String startTime,
                                      int durationMinutes, int setupMinutes, int cleanupMinutes)
            throws SQLException {
        exec("INSERT INTO bookings (booking_ref, binge_id, venue_room_id, customer_id, customer_name, "
            + "customer_email, customer_phone, event_type_id, booking_date, start_time, "
            + "duration_hours, duration_minutes, setup_minutes, cleanup_minutes, number_of_guests, "
            + "base_amount, add_on_amount, guest_amount, total_amount, subtotal_amount, "
            + "venue_room_price, status, payment_status) VALUES ("
            + "'REF" + System.nanoTime() % 100000000L + "', " + BINGE_ID + ", "
            + (roomId == null ? "NULL" : roomId) + ", 100, 'Test Guest', 'g@example.com', '9999999999', 1, "
            + "DATE '" + date + "', TIME '" + startTime + "', "
            + (durationMinutes / 60) + ", " + durationMinutes + ", "
            + setupMinutes + ", " + cleanupMinutes + ", 2, "
            + "1000, 0, 0, 1000, 1000, 0, 'CONFIRMED', 'PENDING')");
    }

    // ── the migration chain itself ───────────────────────────────────────

    @Nested
    @DisplayName("migration chain")
    class MigrationChain {

        @Test
        void everyMigrationAppliedSuccessfully() throws SQLException {
            long failed = scalar("SELECT COUNT(*) FROM flyway_schema_history WHERE success = FALSE");
            assertThat(failed).as("failed Flyway migrations").isZero();

            long applied = scalar("SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE");
            assertThat(applied).as("applied migrations").isGreaterThan(80);
        }

        @Test
        void v81AndV82ColumnsExist() throws SQLException {
            assertThat(columnExists("bookings", "setup_minutes")).isTrue();
            assertThat(columnExists("bookings", "cleanup_minutes")).isTrue();
            assertThat(columnExists("slot_holds", "setup_minutes")).isTrue();
            assertThat(columnExists("binges", "default_setup_minutes")).isTrue();
            assertThat(columnExists("event_types", "setup_minutes")).isTrue();
        }

        @Test
        void v82MadeDurationMinutesNotNull() throws SQLException {
            long nullable = scalar(
                "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_name = 'bookings' AND column_name = 'duration_minutes' "
                + "AND is_nullable = 'YES'");
            assertThat(nullable).as("duration_minutes should be NOT NULL after V82").isZero();
        }

        @Test
        void theOccupancyTriggerIsInstalled() throws SQLException {
            long triggers = scalar(
                "SELECT COUNT(*) FROM pg_trigger "
                + "WHERE tgname = 'trg_booking_occupancy_backstop' AND NOT tgisinternal");
            assertThat(triggers).isEqualTo(1);
        }

        private boolean columnExists(String table, String column) throws SQLException {
            return scalar("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_name = '" + table + "' AND column_name = '" + column + "'") == 1;
        }
    }

    // ── the rule the trigger exists to enforce ───────────────────────────

    @Nested
    @DisplayName("occupancy windows")
    class OccupancyWindows {

        @Test
        @DisplayName("the regression: a cleanup buffer blocks a back-to-back booking")
        void cleanupBufferBlocksBackToBack() throws SQLException {
            LocalDate date = LocalDate.of(2027, 3, 1);
            insertBooking(ROOM_EXCLUSIVE, date, "19:00", 180, 0, 45); // occupies 19:00–22:45

            assertThatThrownBy(() -> insertBooking(ROOM_EXCLUSIVE, date, "22:00", 180, 0, 45))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ROOM_OCCUPANCY_BACKSTOP");
        }

        @Test
        @DisplayName("a start after the buffer clears is still accepted")
        void bufferIsNotALockout() throws SQLException {
            LocalDate date = LocalDate.of(2027, 3, 2);
            insertBooking(ROOM_EXCLUSIVE, date, "19:00", 180, 0, 45); // clears at 22:45
            insertBooking(ROOM_EXCLUSIVE, date, "22:45", 60, 0, 45);  // must succeed
        }

        @Test
        @DisplayName("the later booking's setup buffer also creates the conflict")
        void bothSidesAreWidened() throws SQLException {
            LocalDate date = LocalDate.of(2027, 3, 3);
            insertBooking(ROOM_EXCLUSIVE, date, "19:00", 180, 0, 0); // no cleanup at all

            // Needs 30 minutes of setup, so it really occupies from 21:30.
            assertThatThrownBy(() -> insertBooking(ROOM_EXCLUSIVE, date, "22:00", 120, 30, 0))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ROOM_OCCUPANCY_BACKSTOP");
        }

        @Test
        @DisplayName("zero-buffer venues keep the exact pre-V81 behaviour")
        void zeroBuffersAllowBackToBack() throws SQLException {
            LocalDate date = LocalDate.of(2027, 3, 4);
            insertBooking(ROOM_EXCLUSIVE, date, "19:00", 180, 0, 0);
            insertBooking(ROOM_EXCLUSIVE, date, "22:00", 180, 0, 0); // legal: touching, half-open
        }

        @Test
        @DisplayName("capacity ceilings still hold once buffers are applied")
        void capacityCeilingSurvivesBuffers() throws SQLException {
            LocalDate date = LocalDate.of(2027, 3, 5);
            insertBooking(ROOM_CAPACITY_2, date, "19:00", 120, 0, 60);
            insertBooking(ROOM_CAPACITY_2, date, "20:00", 120, 0, 60);

            assertThatThrownBy(() -> insertBooking(ROOM_CAPACITY_2, date, "20:30", 120, 0, 60))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ROOM_OCCUPANCY_BACKSTOP");
        }

        @Test
        @DisplayName("a status-only transition is not rejected by its own occupancy")
        void statusTransitionOnAnAtCapacityRowIsAllowed() throws SQLException {
            LocalDate date = LocalDate.of(2027, 3, 6);
            insertBooking(ROOM_EXCLUSIVE, date, "19:00", 120, 0, 30);

            // PENDING → CONFIRMED → CHECKED_IN must not re-trigger the conflict count;
            // the row is already part of it.
            exec("UPDATE bookings SET status = 'CHECKED_IN' "
                + "WHERE booking_date = DATE '" + date + "' AND start_time = TIME '19:00'");

            long checkedIn = scalar("SELECT COUNT(*) FROM bookings "
                + "WHERE booking_date = DATE '" + date + "' AND status = 'CHECKED_IN'");
            assertThat(checkedIn).isEqualTo(1);
        }

        @Test
        @DisplayName("cancelling releases the buffered window")
        void cancellationFreesTheWindow() throws SQLException {
            LocalDate date = LocalDate.of(2027, 3, 7);
            insertBooking(ROOM_EXCLUSIVE, date, "19:00", 180, 0, 45);

            exec("UPDATE bookings SET status = 'CANCELLED' "
                + "WHERE booking_date = DATE '" + date + "' AND start_time = TIME '19:00'");

            insertBooking(ROOM_EXCLUSIVE, date, "20:00", 120, 0, 45); // now free
        }

        @Test
        @DisplayName("changing a booking's buffers re-checks occupancy")
        void wideningBuffersOnUpdateIsRechecked() throws SQLException {
            LocalDate date = LocalDate.of(2027, 3, 8);
            insertBooking(ROOM_EXCLUSIVE, date, "19:00", 120, 0, 0);  // 19:00–21:00
            insertBooking(ROOM_EXCLUSIVE, date, "21:00", 120, 0, 0);  // 21:00–23:00, legal today

            // Retro-fitting a 30-minute cleanup onto the first booking would make the
            // pair overlap. The trigger lists setup/cleanup among its UPDATE OF columns
            // precisely so this cannot slip through.
            assertThatThrownBy(() -> exec(
                "UPDATE bookings SET cleanup_minutes = 30 "
                + "WHERE booking_date = DATE '" + date + "' AND start_time = TIME '19:00'"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ROOM_OCCUPANCY_BACKSTOP");
        }
    }

    @Nested
    @DisplayName("schema constraints")
    class SchemaConstraints {

        @Test
        void outOfRangeBufferIsRejected() {
            assertThatThrownBy(() -> exec(
                "UPDATE binges SET default_cleanup_minutes = 999 WHERE id = " + BINGE_ID))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_binge_default_cleanup_minutes");
        }

        @Test
        void durationOutsideTheAllowedRangeIsRejected() throws SQLException {
            assumeThat(scalar(
                "SELECT COUNT(*) FROM pg_constraint WHERE conname = 'ck_booking_duration_minutes' AND convalidated"))
                .as("V82 constraint validated (skipped when legacy rows left it NOT VALID)")
                .isEqualTo(1L);

            assertThatThrownBy(() -> insertBooking(null, LocalDate.of(2027, 4, 1), "10:00", 17, 0, 0))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_booking_duration_minutes");
        }
    }
}
