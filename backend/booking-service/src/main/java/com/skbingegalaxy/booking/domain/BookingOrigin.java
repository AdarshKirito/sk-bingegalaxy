package com.skbingegalaxy.booking.domain;

/**
 * How a reservation came into existence (V85, gap G8).
 *
 * <p>This is not a label — it decides which guards apply. The anti-abuse rules in
 * {@code BookingService} (unpaid-booking limits, pending-duplicate detection,
 * customer freezes, risk flags) all exist to stop a <em>customer</em> misusing the
 * self-service funnel. Applied to a reservation that arrived already paid from a
 * sales channel, they reject real business for reasons that cannot apply.
 *
 * <p>The distinction they encode is <b>"is there a customer with a funnel to
 * abuse?"</b> — not "do we trust this booking". Capacity, approval, operating-hours
 * and locking guards are physical facts about the venue and apply to every origin
 * without exception.
 */
public enum BookingOrigin {

    /** Customer self-service through the booking wizard. Every guard applies. */
    DIRECT(true),

    /**
     * Created by venue staff on a customer's behalf — a walk-in, a phone booking, a
     * correction. Staff are trusted operators inside their own venue, and a walk-in
     * has typically already paid at the counter, so customer-funnel guards would only
     * obstruct them. Capacity and approval guards still apply: an admin cannot
     * double-book a room by asking nicely.
     */
    ADMIN(false),

    /**
     * Delivered by an external sales channel. There is no SK customer account and no
     * funnel; the reservation is usually already paid on the channel's side.
     * Customer-funnel guards are meaningless and actively harmful here.
     */
    CHANNEL(false);

    private final boolean customerFunnelGuardsApply;

    BookingOrigin(boolean customerFunnelGuardsApply) {
        this.customerFunnelGuardsApply = customerFunnelGuardsApply;
    }

    /**
     * Whether the <em>customer-funnel</em> anti-abuse guards apply — concurrent unpaid
     * booking limits, pending-duplicate rejection, customer freezes and risk-flag
     * blocking.
     *
     * <p><b>This is never a blanket bypass.</b> Guards that protect the venue's
     * physical reality — slot locking, occupancy windows, room capacity, binge
     * approval status, operating hours, the database backstop — run for every origin
     * and have no opinion about where a booking came from.
     */
    public boolean customerFunnelGuardsApply() {
        return customerFunnelGuardsApply;
    }

    /** True when this origin requires {@code externalSource}/{@code externalRef} (enforced by the V85 CHECK). */
    public boolean requiresExternalReference() {
        return this == CHANNEL;
    }

    /** Null-safe parse; unknown or missing values fall back to the strictest origin. */
    public static BookingOrigin parseOrDirect(String raw) {
        if (raw == null || raw.isBlank()) return DIRECT;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            // Fail closed: an unrecognised origin gets the full guard set rather than
            // silently skipping abuse protection.
            return DIRECT;
        }
    }
}
