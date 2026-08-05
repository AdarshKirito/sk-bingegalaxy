package com.skbingegalaxy.distribution.listing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Listing readiness (slice 4)")
class ListingReadinessPolicyTest {

    private final ListingReadinessPolicy policy = new ListingReadinessPolicy();

    private static Map<String, String> content(String... fields) {
        Map<String, String> m = new HashMap<>();
        for (String f : fields) m.put(f, "value");
        return m;
    }

    @Test
    @DisplayName("the SAME listing can be ready for one destination and not another")
    void readinessIsPerDestination() {
        // Enough for the simulator, nowhere near enough for Viator. This is the whole
        // reason readiness is stored per (listing x destination) rather than per listing.
        Map<String, String> minimal = content("title", "price");

        assertThat(policy.evaluate("SIMULATOR", minimal).publishable()).isTrue();
        assertThat(policy.evaluate("VIATOR", minimal).publishable()).isFalse();
    }

    @Test
    @DisplayName("an UNKNOWN destination gets the strictest requirements, not none")
    void unknownDestinationFailsClosed() {
        // An empty requirement set would compute 100%, and ck_live_requires_ready would
        // then happily allow LIVE for a destination nobody has modelled.
        ListingReadinessPolicy.Readiness r = policy.evaluate("SOME_NEW_MARKETPLACE", content("title"));

        assertThat(r.publishable()).isFalse();
        assertThat(policy.requirementsFor("SOME_NEW_MARKETPLACE"))
            .hasSizeGreaterThanOrEqualTo(policy.requirementsFor("VIATOR").size());
    }

    @Test
    @DisplayName("a null destination also fails closed")
    void nullDestinationFailsClosed() {
        assertThat(policy.evaluate(null, content("title")).publishable()).isFalse();
    }

    @Test
    @DisplayName("a blank value counts as missing, not as satisfied")
    void blankIsMissing() {
        Map<String, String> blanks = new HashMap<>(content("title"));
        blanks.put("price", "   ");

        // An empty description satisfies a schema, not a traveller.
        assertThat(policy.evaluate("SIMULATOR", blanks).publishable()).isFalse();
    }

    @Test
    @DisplayName("percentage floors rather than rounding up toward 'finished'")
    void percentageFloors() {
        // Viator wants 7 fields; supply 6. 6/7 = 85.7 -> 85, never 86 or 90.
        Map<String, String> six = content("title", "description", "photos",
            "meetingPoint", "duration", "cancellationPolicy");

        assertThat(policy.evaluate("VIATOR", six).percent()).isEqualTo(85);
    }

    @Test
    @DisplayName("blocking reasons are instructions, not field names")
    void reasonsAreActionable() {
        ListingReadinessPolicy.Readiness r = policy.evaluate("VIATOR", content("title"));

        // "meetingPoint is missing" is a field name; the operator needs to know what to
        // do and why, which is what makes Blocked reachable by the person who can fix it.
        assertThat(r.blockingReasons()).anySatisfy(reason -> {
            assertThat(reason).contains(" ");
            assertThat(reason).doesNotMatch("^[a-z]+[A-Z].*");
        });
        assertThat(r.blockingReasons()).anyMatch(s -> s.toLowerCase().contains("meeting point"));
    }

    @Test
    @DisplayName("Google requires a landing page — a feed entry without one advertises a 404")
    void googleRequiresLandingUrl() {
        Map<String, String> withoutUrl = content("title", "description", "photos", "price");

        assertThat(policy.evaluate("GOOGLE_TTD", withoutUrl).publishable()).isFalse();
        assertThat(policy.evaluate("GOOGLE_TTD", withoutUrl).blockingReasons())
            .anyMatch(s -> s.toLowerCase().contains("land on"));
    }

    @Test
    @DisplayName("a fully satisfied listing reaches exactly 100 with no reasons")
    void completeListingIsPublishable() {
        Map<String, String> full = content("title", "description", "photos",
            "meetingPoint", "duration", "cancellationPolicy", "price");

        ListingReadinessPolicy.Readiness r = policy.evaluate("VIATOR", full);

        assertThat(r.percent()).isEqualTo(100);
        assertThat(r.blockingReasons()).isEmpty();
        assertThat(r.publishable()).isTrue();
    }

    @Test
    @DisplayName("null content is handled without throwing")
    void nullContentIsSafe() {
        // Reached when a listing has never been edited. It must report 0 and a full list
        // of instructions, not fail the request that was trying to show them.
        ListingReadinessPolicy.Readiness r = policy.evaluate("VIATOR", null);
        assertThat(r.percent()).isZero();
        assertThat(r.blockingReasons()).hasSize(policy.requirementsFor("VIATOR").size());
    }
}
