package com.skbingegalaxy.booking.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Register item <b>TEST-01</b>, second half: the occupancy backstop under <b>real
 * concurrent writers</b>.
 *
 * <p>{@code OccupancyBackstopIT} proves the trigger's <em>logic</em> — given one
 * writer, does it accept and reject the right rows. That is necessary but not
 * sufficient. The backstop's whole reason for existing is the case where the
 * application's {@code pg_advisory_xact_lock} was bypassed, which by definition means
 * two writers are racing. A trigger that computes the right answer but computes it
 * against a snapshot taken before a competing insert committed would still let an
 * oversell through, and no single-threaded test can see that.
 *
 * <p>The trigger defends itself by taking its <b>own</b> advisory lock
 * ({@code booking-room-backstop:<room>:<date>}) before counting. These tests fire N
 * simultaneous transactions at one slot and assert that exactly {@code capacity}
 * survive — the property that matters, stated as a number rather than as a hope.
 *
 * <p>A release barrier ({@code CountDownLatch}) makes the threads start together;
 * without it, JDBC connection setup staggers them enough that the race never happens
 * and the test passes vacuously.
 *
 * <p>Gated on {@code -Dtestcontainers.enabled=true}. Run locally with
 * {@code ./scripts/run-integration-tests.ps1}; the Jenkinsfile sets it for CI.
 */
@Testcontainers
@EnabledIfSystemProperty(
    named = "testcontainers.enabled", matches = "true",
    disabledReason = "needs a Docker daemon; enable with -Dtestcontainers.enabled=true")
@DisplayName("Occupancy backstop under concurrent writers")
class OccupancyContentionIT {

    private static PostgreSQLContainer<?> postgres;

    private static final long BINGE_ID = 1L;
    private static final long ROOM_EXCLUSIVE = 1L;   // capacity 1
    private static final long ROOM_CAPACITY_3 = 2L;  // capacity 3
    private static final long ROOMLESS_BINGE = 2L;   // no rooms, max_concurrent_bookings = 2

    /** Enough contenders that a lost race is overwhelmingly likely if the lock is broken. */
    private static final int CONTENDERS = 12;

    @BeforeAll
    static void startDatabaseAndMigrate() throws SQLException {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("booking_db")
            .withUsername("test")
            .withPassword("test");
        postgres.start();

        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO binges (id, name, admin_id, country, currency, timezone, status, active) VALUES "
                + "(" + BINGE_ID + ", 'Contention Venue', 1, 'IN', 'INR', 'Asia/Kolkata', 'APPROVED', TRUE), "
                + "(" + ROOMLESS_BINGE + ", 'Roomless Venue', 1, 'IN', 'INR', 'Asia/Kolkata', 'APPROVED', TRUE)");
            st.execute("UPDATE binges SET max_concurrent_bookings = 2 WHERE id = " + ROOMLESS_BINGE);
            st.execute("INSERT INTO venue_rooms (id, binge_id, name, room_type, capacity, active, status, sort_order) VALUES "
                + "(" + ROOM_EXCLUSIVE + ", " + BINGE_ID + ", 'Exclusive', 'PRIVATE_ROOM', 1, TRUE, 'APPROVED', 0), "
                + "(" + ROOM_CAPACITY_3 + ", " + BINGE_ID + ", 'Shared', 'MAIN_HALL', 3, TRUE, 'APPROVED', 1)");
            st.execute("INSERT INTO event_types (id, binge_id, name, base_price, hourly_rate, price_per_guest, "
                + "min_hours, max_hours, active) VALUES "
                + "(1, " + BINGE_ID + ", 'Celebration', 1000, 500, 0, 1, 8, TRUE), "
                + "(2, " + ROOMLESS_BINGE + ", 'Celebration', 1000, 500, 0, 1, 8, TRUE)");
        }
    }

    @AfterAll
    static void stop() {
        if (postgres != null) postgres.stop();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    /**
     * Fire {@code CONTENDERS} inserts at the same slot simultaneously.
     *
     * <p>Every thread opens its own connection and waits on a barrier, so they hit the
     * trigger together rather than in connection-setup order. Each runs in its own
     * transaction — that is what makes {@code pg_advisory_xact_lock} inside the trigger
     * meaningful, since the lock is released at commit or rollback.
     *
     * @return how many inserts committed
     */
    private int raceForSlot(Long bingeId, Long roomId, LocalDate date, String startTime,
                            int durationMinutes, int cleanupMinutes, long eventTypeId)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONTENDERS);
        CountDownLatch ready = new CountDownLatch(CONTENDERS);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();

        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < CONTENDERS; i++) {
                final int n = i;
                tasks.add(() -> {
                    try (Connection c = connect()) {
                        c.setAutoCommit(false);
                        ready.countDown();
                        go.await(20, TimeUnit.SECONDS);
                        try (Statement st = c.createStatement()) {
                            st.execute(insertSql(bingeId, roomId, date, startTime,
                                durationMinutes, cleanupMinutes, eventTypeId, n));
                            c.commit();
                            succeeded.incrementAndGet();
                        } catch (SQLException expectedForLosers) {
                            // The backstop rejected this writer — the correct outcome for
                            // everyone past capacity. Roll back so the advisory lock frees.
                            c.rollback();
                        }
                    }
                    return null;
                });
            }

            List<Future<Void>> futures = new ArrayList<>();
            for (Callable<Void> t : tasks) futures.add(pool.submit(t));

            assertThat(ready.await(30, TimeUnit.SECONDS))
                .as("all contenders reached the barrier").isTrue();
            go.countDown();                       // release them together

            for (Future<Void> f : futures) f.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        return succeeded.get();
    }

    private String insertSql(Long bingeId, Long roomId, LocalDate date, String startTime,
                             int durationMinutes, int cleanupMinutes, long eventTypeId, int n) {
        return "INSERT INTO bookings (booking_ref, binge_id, venue_room_id, customer_id, customer_name, "
            + "customer_email, customer_phone, event_type_id, booking_date, start_time, "
            + "duration_hours, duration_minutes, setup_minutes, cleanup_minutes, number_of_guests, "
            + "base_amount, add_on_amount, guest_amount, total_amount, subtotal_amount, "
            + "venue_room_price, status, payment_status) VALUES ("
            + "'RACE" + System.nanoTime() % 1000000 + n + "', " + bingeId + ", "
            + (roomId == null ? "NULL" : roomId) + ", 100, 'Racer', 'r@example.com', '9999999999', "
            + eventTypeId + ", DATE '" + date + "', TIME '" + startTime + "', "
            + (durationMinutes / 60) + ", " + durationMinutes + ", 0, " + cleanupMinutes + ", 2, "
            + "1000, 0, 0, 1000, 1000, 0, 'CONFIRMED', 'PENDING')";
    }

    private long countActive(Long roomId, LocalDate date) throws SQLException {
        try (Connection c = connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT COUNT(*) FROM bookings WHERE booking_date = DATE '" + date + "'"
                 + (roomId == null ? " AND venue_room_id IS NULL" : " AND venue_room_id = " + roomId)
                 + " AND status NOT IN ('CANCELLED','NO_SHOW')")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    @DisplayName("12 writers racing one exclusive room: exactly 1 wins")
    void exclusiveRoomAdmitsExactlyOne() throws Exception {
        LocalDate date = LocalDate.of(2028, 1, 10);

        int committed = raceForSlot(BINGE_ID, ROOM_EXCLUSIVE, date, "19:00", 180, 0, 1L);

        assertThat(committed)
            .as("a capacity-1 room must admit exactly one of %d simultaneous writers", CONTENDERS)
            .isEqualTo(1);
        assertThat(countActive(ROOM_EXCLUSIVE, date)).isEqualTo(1);
    }

    @Test
    @DisplayName("12 writers racing a capacity-3 room: exactly 3 win")
    void capacityThreeRoomAdmitsExactlyThree() throws Exception {
        LocalDate date = LocalDate.of(2028, 1, 11);

        int committed = raceForSlot(BINGE_ID, ROOM_CAPACITY_3, date, "19:00", 180, 0, 1L);

        // The interesting number: not 1, not 12. The trigger must count correctly
        // under contention, not merely serialise.
        assertThat(committed)
            .as("a capacity-3 room must admit exactly three of %d simultaneous writers", CONTENDERS)
            .isEqualTo(3);
        assertThat(countActive(ROOM_CAPACITY_3, date)).isEqualTo(3);
    }

    @Test
    @DisplayName("turnover buffers hold under contention, not just single-threaded")
    void cleanupBufferHoldsUnderContention() throws Exception {
        // Same room, staggered starts 30 minutes apart, each needing 60 minutes of
        // cleanup. Every pair overlaps once buffers are applied, so a capacity-1 room
        // still admits exactly one — proving the buffered window is evaluated inside
        // the trigger's lock rather than against a pre-race snapshot.
        LocalDate date = LocalDate.of(2028, 1, 12);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();

        try {
            List<Future<Void>> futures = new ArrayList<>();
            String[] starts = {"19:00", "19:30", "20:00", "20:30"};
            for (int i = 0; i < starts.length; i++) {
                final String start = starts[i];
                final int n = i;
                futures.add(pool.submit(() -> {
                    try (Connection c = connect()) {
                        c.setAutoCommit(false);
                        ready.countDown();
                        go.await(20, TimeUnit.SECONDS);
                        try (Statement st = c.createStatement()) {
                            st.execute(insertSql(BINGE_ID, ROOM_EXCLUSIVE, date, start, 60, 60, 1L, n));
                            c.commit();
                            succeeded.incrementAndGet();
                        } catch (SQLException rejected) {
                            c.rollback();
                        }
                    }
                    return null;
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<Void> f : futures) f.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded.get())
            .as("60-minute cleanup makes every 30-minute-apart start overlap")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("room-less venue ceiling holds under contention")
    void roomlessVenueCeilingHoldsUnderContention() throws Exception {
        // The trigger's second branch: a venue with no bookable rooms is a single
        // space bounded by max_concurrent_bookings. It uses a different advisory-lock
        // keyspace, so it needs its own contention proof.
        LocalDate date = LocalDate.of(2028, 1, 13);

        int committed = raceForSlot(ROOMLESS_BINGE, null, date, "19:00", 180, 0, 2L);

        assertThat(committed)
            .as("max_concurrent_bookings = 2 must admit exactly two of %d writers", CONTENDERS)
            .isEqualTo(2);
        assertThat(countActive(null, date)).isEqualTo(2);
    }
}
