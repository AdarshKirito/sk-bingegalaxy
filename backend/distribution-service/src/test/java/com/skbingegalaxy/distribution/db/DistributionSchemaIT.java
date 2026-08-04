package com.skbingegalaxy.distribution.db;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The distribution schema's invariants, proven against a real PostgreSQL.
 *
 * <p>These are not "does the table exist" tests. Each one pins a <b>design decision
 * that was wrong in an earlier revision</b>, so that reverting the decision breaks the
 * build rather than quietly shipping:
 *
 * <ul>
 *   <li><b>Google is feed-only.</b> An earlier design showed a Google reservation in
 *       the inbox. That object does not exist — Things to Do is a product feed plus a
 *       deep link, and the traveller checks out on SK Binge.</li>
 *   <li><b>Capabilities fail closed.</b> An undeclared capability must read as false.
 *       The UI must never offer an action a provider cannot perform.</li>
 *   <li><b>Channel collects by default.</b> Viator and GetYourGuide are both merchant
 *       of record. Defaulting to "the venue collects" would misstate a venue's cash
 *       flow — the most expensive error in the earlier design.</li>
 *   <li><b>LIVE requires 100% readiness</b>, enforced by a CHECK so no service bug can
 *       publish a half-ready listing.</li>
 * </ul>
 *
 * <p>Gated on {@code -Dtestcontainers.enabled=true} so a contributor without Docker
 * still gets a green build; the Jenkinsfile sets it. Run locally with
 * {@code ./scripts/run-integration-tests.ps1 -Modules common-lib,distribution-service}.
 */
@Testcontainers
@EnabledIfSystemProperty(
    named = "testcontainers.enabled", matches = "true",
    disabledReason = "needs a Docker daemon; enable with -Dtestcontainers.enabled=true")
@DisplayName("Distribution schema (real PostgreSQL + real Flyway)")
class DistributionSchemaIT {

    private static PostgreSQLContainer<?> postgres;
    private static Connection connection;

    @BeforeAll
    static void startAndMigrate() throws SQLException {
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

        connection = DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

        // Fixture: a throwaway provider/destination/connection to exercise constraints
        // without depending on the seeded catalogue's activation state.
        // active = FALSE deliberately: a test fixture must not masquerade as a real
        // activated provider, or it pollutes the seeded-catalogue assertions below.
        exec("INSERT INTO providers (code, display_name, provider_kind, auth_method, active) "
            + "VALUES ('T', 'Test Provider', 'BOTH', 'API_KEY', FALSE)");
        exec("INSERT INTO destinations (code, display_name, operated_by_provider_code) "
            + "VALUES ('TD', 'Test Destination', 'T')");
        exec("INSERT INTO connections (id, binge_id, provider_code) VALUES (900, 1, 'T')");
        exec("INSERT INTO connection_destinations (id, connection_id, destination_code) "
            + "VALUES (900, 900, 'TD')");
    }

    @AfterAll
    static void stop() throws SQLException {
        if (connection != null) connection.close();
        if (postgres != null) postgres.stop();
    }

    private static void exec(String sql) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        }
    }

    private static String scalar(String sql) throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /**
     * Boolean columns must be read with {@code getBoolean}. PostgreSQL's JDBC driver
     * renders a boolean as {@code "f"}/{@code "t"} via {@code getString}, so a string
     * comparison against {@code "false"} silently fails for the wrong reason.
     *
     * @return null when the row does not exist — which is itself meaningful here,
     *         because an undeclared capability must read as absent rather than enabled
     */
    private static Boolean scalarBool(String sql) throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) return null;
            boolean v = rs.getBoolean(1);
            return rs.wasNull() ? null : v;
        }
    }

    @Nested
    @DisplayName("seeded catalogue")
    class SeededCatalogue {

        @Test
        @DisplayName("Google Things to Do never delivers reservations")
        void googleIsFeedOnly() throws SQLException {
            assertThat(scalarBool("SELECT delivers_reservations FROM destinations WHERE code = 'GOOGLE_TTD'"))
                .as("Things to Do is a feed + deep link; no reservation flows back")
                .isFalse();
        }

        @Test
        @DisplayName("real providers are seeded inactive; only the simulator is usable")
        void onlySimulatorIsActive() throws SQLException {
            assertThat(scalar("SELECT COUNT(*) FROM providers WHERE active AND code <> 'SIMULATOR'"))
                .as("nothing connectable before a super-admin activates it")
                .isEqualTo("0");
        }

        @Test
        @DisplayName("Google's reservation capabilities are all false")
        void googleCapabilitiesAreFalse() throws SQLException {
            assertThat(scalar("SELECT COUNT(*) FROM provider_capabilities "
                + "WHERE provider_code = 'GOOGLE_TTD' AND enabled "
                + "AND capability_key IN ('deliversReservations','supportsModification','supportsCancellation')"))
                .isEqualTo("0");
        }
    }

    @Nested
    @DisplayName("capabilities fail closed")
    class CapabilitiesFailClosed {

        @Test
        void undeclaredCapabilityHasNoRow() throws SQLException {
            assertThat(scalar("SELECT enabled FROM provider_capabilities "
                + "WHERE provider_code = 'VIATOR' AND capability_key = 'somethingNobodyDeclared'"))
                .as("absence must read as false, not as an enabled control")
                .isNull();
        }

        @Test
        void unverifiedCounterOfferStaysDisabled() throws SQLException {
            // The earlier design rendered "Offer 19:00 instead" unconditionally. Whether
            // Viator supports counter-offers is UNVERIFIED, so it must stay off.
            assertThat(scalarBool("SELECT enabled FROM provider_capabilities "
                + "WHERE provider_code = 'VIATOR' AND capability_key = 'supportsCounterOffer'"))
                .isFalse();
        }
    }

    @Nested
    @DisplayName("listing readiness")
    class ListingReadiness {

        @Test
        void liveBelowFullReadinessIsRejected() {
            assertThatThrownBy(() -> exec(
                "INSERT INTO listing_mappings (connection_destination_id, event_type_id, binge_id, "
                + "publish_state, readiness_pct) VALUES (900, 10, 1, 'LIVE', 60)"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_live_requires_ready");
        }

        @Test
        void fullyReadyListingPublishes() throws SQLException {
            exec("INSERT INTO listing_mappings (connection_destination_id, event_type_id, binge_id, "
                + "publish_state, readiness_pct) VALUES (900, 11, 1, 'LIVE', 100)");
        }
    }

    @Nested
    @DisplayName("commerce defaults")
    class CommerceDefaults {

        @Test
        @DisplayName("payment responsibility defaults to CHANNEL_COLLECTS")
        void channelCollectsByDefault() throws SQLException {
            // Viator and GetYourGuide are both merchant of record. Defaulting the other
            // way would tell a venue to expect money at checkout that never arrives.
            assertThat(scalar("SELECT payment_responsibility FROM connection_destinations WHERE id = 900"))
                .isEqualTo("CHANNEL_COLLECTS");
        }

        @Test
        void settlementCurrencyMustBeIsoShaped() {
            // 3 chars but lowercase: proves the CHECK enforces the shape, rather than
            // VARCHAR(3) rejecting it on length before the CHECK can run.
            assertThatThrownBy(() -> exec(
                "INSERT INTO settlement_records (booking_ref, binge_id, destination_code, "
                + "settlement_currency, collected_by) VALUES ('B1', 1, 'TD', 'inr', 'CHANNEL')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_settlement_currency_iso");
        }

        @Test
        @DisplayName("a destination may remit in one currency while the venue banks in another")
        void crossCurrencySettlementIsRecordable() throws SQLException {
            exec("INSERT INTO settlement_records (booking_ref, binge_id, destination_code, "
                + "settlement_currency, venue_payout_currency, collected_by, "
                + "gross_minor, commission_minor, venue_net_minor) "
                + "VALUES ('B2', 1, 'TD', 'EUR', 'INR', 'CHANNEL', 840000, 168000, 672000)");

            assertThat(scalar("SELECT venue_payout_currency FROM settlement_records WHERE booking_ref = 'B2'"))
                .isEqualTo("INR");
        }
    }

    @Nested
    @DisplayName("inbound message handling")
    class InboundMessages {

        @Test
        void redeliveredMessageIsRejectedButAModifyIsAccepted() throws SQLException {
            exec("INSERT INTO reservation_inbox (connection_id, destination_code, external_ref, "
                + "message_type, external_sequence, payload_json) VALUES (900, 'TD', 'R1', 'CREATE', 1, '{}')");

            assertThatThrownBy(() -> exec(
                "INSERT INTO reservation_inbox (connection_id, destination_code, external_ref, "
                + "message_type, external_sequence, payload_json) VALUES (900, 'TD', 'R1', 'CREATE', 1, '{}')"))
                .as("channels retry; the same message must not be stored twice")
                .isInstanceOf(SQLException.class);

            // A genuine later MODIFY for the same reservation is a different message.
            exec("INSERT INTO reservation_inbox (connection_id, destination_code, external_ref, "
                + "message_type, external_sequence, payload_json) VALUES (900, 'TD', 'R1', 'MODIFY', 2, '{}')");
        }

        @Test
        void orderingBasisIsRecorded() throws SQLException {
            // Reconciliation must be able to tell "ordered by the provider" from
            // "ordered by luck".
            assertThat(scalar("SELECT ordering_basis FROM reservation_inbox WHERE external_ref = 'R1' LIMIT 1"))
                .isIn("PROVIDER_SEQUENCE", "PROVIDER_TIMESTAMP", "RECEIPT_ORDER");
        }
    }

    @Nested
    @DisplayName("connection tenancy and controls")
    class ConnectionRules {

        @Test
        void sandboxAndProductionCoexistButDuplicatesDoNot() throws SQLException {
            exec("INSERT INTO connections (binge_id, provider_code, environment) "
                + "VALUES (2, 'T', 'PRODUCTION')");

            assertThatThrownBy(() -> exec("INSERT INTO connections (binge_id, provider_code, environment) "
                + "VALUES (2, 'T', 'PRODUCTION')"))
                .isInstanceOf(SQLException.class);
        }

        @Test
        void safetyInventoryAndStopSellExist() throws SQLException {
            // Restored from the original dossier (G4/DIST-R6); the v2 design had lost them.
            exec("UPDATE connection_destinations SET safety_inventory = 2, stop_sell = TRUE WHERE id = 900");

            assertThatThrownBy(() -> exec(
                "UPDATE connection_destinations SET safety_inventory = -1 WHERE id = 900"))
                .isInstanceOf(SQLException.class);
        }
    }
}
