package com.skbingegalaxy.distribution.listing;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Whether a listing is complete enough to publish to a <b>particular</b> destination.
 *
 * <p><b>Readiness is per (listing × destination), never per listing.</b> Each marketplace
 * demands different content, so a listing that satisfies one can be genuinely incomplete
 * for another. Collapsing this into a single "is this listing ready?" flag would either
 * block publishing to an easy destination because a hard one wants more, or claim
 * readiness for a destination whose requirements were never checked.
 *
 * <p><b>Requirements are declared per destination and default to STRICT.</b> An unknown
 * destination code returns the strictest known set rather than an empty one: an empty
 * requirement list would compute 100% readiness for a destination nobody has modelled,
 * and the {@code ck_live_requires_ready} CHECK would happily let it go LIVE. Failing
 * open on a marketplace integration means publishing content a partner will reject —
 * or worse, accept and then hold the venue to.
 *
 * <p>Blocking reasons are written for the person who can fix them. "meetingPoint is
 * missing" is a field name; "Add a meeting point — Viator shows this to travellers
 * before they book" is an instruction.
 */
@Component
public class ListingReadinessPolicy {

    /** One required piece of content, and how to say what is missing. */
    public record Requirement(String field, String instruction) {}

    /**
     * Requirements per destination.
     *
     * <p>Sourced from each provider's published listing requirements. Where a
     * requirement could not be confirmed from official documentation it is deliberately
     * NOT included — inventing a requirement blocks a venue for no reason, and the
     * dossier's rule is that unverified claims must not drive behaviour.
     */
    private static final Map<String, List<Requirement>> BY_DESTINATION = Map.of(
        "VIATOR", List.of(
            new Requirement("title", "Add a listing title travellers will see in search results."),
            new Requirement("description", "Add a description of what the experience includes."),
            new Requirement("photos", "Add at least one photo — listings without images are rejected."),
            new Requirement("meetingPoint", "Add a meeting point or address travellers arrive at."),
            new Requirement("duration", "Set how long the experience lasts."),
            new Requirement("cancellationPolicy", "Set a cancellation policy."),
            new Requirement("price", "Set a price for this experience.")),
        "GETYOURGUIDE", List.of(
            new Requirement("title", "Add a listing title."),
            new Requirement("description", "Add a description of what the experience includes."),
            new Requirement("photos", "Add at least one photo."),
            new Requirement("meetingPoint", "Add a meeting point travellers arrive at."),
            new Requirement("duration", "Set how long the experience lasts."),
            new Requirement("cancellationPolicy", "Set a cancellation policy."),
            new Requirement("price", "Set a price for this experience."),
            new Requirement("languages", "List the languages the experience is delivered in.")),
        "GOOGLE_TTD", List.of(
            new Requirement("title", "Add a listing title."),
            new Requirement("description", "Add a description."),
            new Requirement("photos", "Add at least one photo."),
            new Requirement("price", "Set a price — the feed requires one."),
            // Google's feed is a deep link, so the destination URL is the whole point:
            // a feed entry with no landing page is an advert for a 404.
            new Requirement("landingUrl", "A bookable page must exist for travellers to land on.")),
        "SIMULATOR", List.of(
            new Requirement("title", "Add a listing title."),
            new Requirement("price", "Set a price."))
    );

    /**
     * Used for any destination not declared above. The union of every known requirement,
     * so an unmodelled destination is hard to publish to rather than trivially easy.
     */
    private static List<Requirement> strictestKnown() {
        Map<String, Requirement> union = new LinkedHashMap<>();
        BY_DESTINATION.values().stream()
            .flatMap(List::stream)
            .forEach(r -> union.putIfAbsent(r.field(), r));
        return List.copyOf(union.values());
    }

    public List<Requirement> requirementsFor(String destinationCode) {
        if (destinationCode == null) return strictestKnown();
        return BY_DESTINATION.getOrDefault(destinationCode.toUpperCase(Locale.ROOT), strictestKnown());
    }

    /** The outcome of an evaluation. */
    public record Readiness(int percent, List<String> blockingReasons) {
        public boolean publishable() {
            return percent == 100;
        }
    }

    /**
     * Evaluate a listing's content against one destination's requirements.
     *
     * @param present fields the listing actually has content for. A field present with a
     *                blank value counts as missing — an empty description satisfies a
     *                schema, not a traveller.
     */
    public Readiness evaluate(String destinationCode, Map<String, String> present) {
        List<Requirement> required = requirementsFor(destinationCode);
        if (required.isEmpty()) {
            // Cannot happen with the current tables, but returning 100 here would be the
            // fail-open this class exists to avoid, so it is stated rather than assumed.
            return new Readiness(0, List.of("No requirements are defined for this destination."));
        }

        List<String> missing = new ArrayList<>();
        for (Requirement r : required) {
            String value = present == null ? null : present.get(r.field());
            if (value == null || value.isBlank()) {
                missing.add(r.instruction());
            }
        }

        int satisfied = required.size() - missing.size();
        // Integer division floors, which is the safe direction: 6 of 7 fields is 85%,
        // never rounded up to something that looks finished.
        int percent = (satisfied * 100) / required.size();
        return new Readiness(percent, List.copyOf(missing));
    }
}
