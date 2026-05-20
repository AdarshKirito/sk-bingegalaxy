package com.skbingegalaxy.booking.service.statemachine;

import com.skbingegalaxy.booking.entity.Booking;
import com.skbingegalaxy.booking.entity.BookingEventType;
import com.skbingegalaxy.booking.repository.BookingRepository;
import com.skbingegalaxy.booking.service.BookingEventLogService;
import com.skbingegalaxy.booking.web.RequestContext;
import com.skbingegalaxy.common.enums.BookingStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.skbingegalaxy.booking.service.statemachine.TransitionActor.ROLE_ADMIN;
import static com.skbingegalaxy.booking.service.statemachine.TransitionActor.ROLE_CUSTOMER;
import static com.skbingegalaxy.booking.service.statemachine.TransitionActor.ROLE_SUPER_ADMIN;
import static com.skbingegalaxy.booking.service.statemachine.TransitionActor.ROLE_SYSTEM;

/**
 * Centralized booking state-transition engine.
 *
 * <p>This is the <em>single</em> place in the codebase that mutates
 * {@link Booking#getStatus()}. Every controller/service/listener/scheduler
 * that needs to advance a booking's lifecycle MUST go through
 * {@link #transition(Booking, BookingTransitionEvent, TransitionActor, String)}
 * (normal) or {@link #override(Booking, BookingStatus, TransitionActor, String)}
 * (super-admin recovery). Direct {@code booking.setStatus(...)} calls are a
 * code-review smell.
 *
 * <h3>Why centralize?</h3>
 * <ul>
 *   <li><b>Impossible states are impossible.</b> The transition table is the
 *       law: anything not declared here throws
 *       {@link InvalidTransitionException} (409 Conflict).</li>
 *   <li><b>Actor-aware.</b> Each rule declares which roles may trigger it,
 *       so a customer can never run an admin-only transition even if a
 *       controller forgets a guard.</li>
 *   <li><b>Audit-by-construction.</b> Every successful transition emits a
 *       {@link BookingEventLog} entry with previousStatus / newStatus /
 *       actor / reason / IP / User-Agent — no caller can forget.</li>
 *   <li><b>Idempotent.</b> Re-applying the same event to a booking already in
 *       the target state is a no-op (used by Kafka retries).</li>
 *   <li><b>Override path.</b> Super-admin recovery (e.g.
 *       {@code CANCELLED → CONFIRMED} for wrongful cancel,
 *       {@code NO_SHOW → CHECKED_IN} for misflagged customer) is supported
 *       via {@link #override}, with reason mandatory and the audit event
 *       tagged {@code MANUAL_REVIEW_FLAGGED}.</li>
 * </ul>
 *
 * <h3>Transition table</h3>
 * <pre>
 *   PENDING    + PAYMENT_SUCCEEDED  → CONFIRMED   (SYSTEM)
 *   PENDING    + ADMIN_CONFIRM      → CONFIRMED   (ADMIN, SUPER_ADMIN)
 *   PENDING    + CUSTOMER_CANCEL    → CANCELLED   (CUSTOMER)
 *   PENDING    + ADMIN_CANCEL       → CANCELLED   (ADMIN, SUPER_ADMIN)
 *   PENDING    + SYSTEM_AUTO_CANCEL → CANCELLED   (SYSTEM)
 *   PENDING    + MARK_NO_SHOW       → NO_SHOW     (SYSTEM)
 *
 *   CONFIRMED  + CHECK_IN           → CHECKED_IN  (ADMIN, SUPER_ADMIN)
 *   CONFIRMED  + CUSTOMER_CANCEL    → CANCELLED   (CUSTOMER)
 *   CONFIRMED  + ADMIN_CANCEL       → CANCELLED   (ADMIN, SUPER_ADMIN)
 *   CONFIRMED  + SYSTEM_AUTO_CANCEL → CANCELLED   (SYSTEM)
 *   CONFIRMED  + MARK_NO_SHOW       → NO_SHOW     (SYSTEM)
 *
 *   CHECKED_IN + CHECK_OUT          → COMPLETED   (ADMIN, SUPER_ADMIN, SYSTEM)
 *   CHECKED_IN + UNDO_CHECK_IN      → CONFIRMED   (ADMIN, SUPER_ADMIN; reason required)
 *
 *   COMPLETED, CANCELLED, NO_SHOW are TERMINAL — only reachable via {@link #override}.
 * </pre>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingStateMachine {

    private final BookingRepository bookingRepository;
    private final BookingEventLogService eventLogService;

    // ─── Transition rule (immutable, declared once at class load) ───────────

    private static final class Rule {
        final BookingStatus to;
        final BookingEventType auditEvent;
        final Set<String> allowedRoles;
        final boolean reasonRequired;

        Rule(BookingStatus to, BookingEventType auditEvent, boolean reasonRequired,
             String... roles) {
            this.to = to;
            this.auditEvent = auditEvent;
            this.reasonRequired = reasonRequired;
            this.allowedRoles = Set.of(roles);
        }
    }

    // (currentStatus, event) -> Rule
    private static final Map<BookingStatus, Map<BookingTransitionEvent, Rule>> TABLE;

    static {
        TABLE = new EnumMap<>(BookingStatus.class);
        for (BookingStatus s : BookingStatus.values()) {
            TABLE.put(s, new EnumMap<>(BookingTransitionEvent.class));
        }

        // ── PENDING ──
        TABLE.get(BookingStatus.PENDING).put(BookingTransitionEvent.PAYMENT_SUCCEEDED,
            new Rule(BookingStatus.CONFIRMED, BookingEventType.CONFIRMED, false,
                ROLE_SYSTEM));
        TABLE.get(BookingStatus.PENDING).put(BookingTransitionEvent.ADMIN_CONFIRM,
            new Rule(BookingStatus.CONFIRMED, BookingEventType.CONFIRMED, false,
                ROLE_ADMIN, ROLE_SUPER_ADMIN));
        TABLE.get(BookingStatus.PENDING).put(BookingTransitionEvent.CUSTOMER_CANCEL,
            new Rule(BookingStatus.CANCELLED, BookingEventType.CANCELLED, false,
                ROLE_CUSTOMER));
        TABLE.get(BookingStatus.PENDING).put(BookingTransitionEvent.ADMIN_CANCEL,
            new Rule(BookingStatus.CANCELLED, BookingEventType.CANCELLED, false,
                ROLE_ADMIN, ROLE_SUPER_ADMIN));
        TABLE.get(BookingStatus.PENDING).put(BookingTransitionEvent.SYSTEM_AUTO_CANCEL,
            new Rule(BookingStatus.CANCELLED, BookingEventType.CANCELLED, false,
                ROLE_SYSTEM));
        TABLE.get(BookingStatus.PENDING).put(BookingTransitionEvent.MARK_NO_SHOW,
            new Rule(BookingStatus.NO_SHOW, BookingEventType.NO_SHOW, false,
                ROLE_SYSTEM));

        // ── CONFIRMED ──
        TABLE.get(BookingStatus.CONFIRMED).put(BookingTransitionEvent.CHECK_IN,
            new Rule(BookingStatus.CHECKED_IN, BookingEventType.CHECKED_IN, false,
                ROLE_ADMIN, ROLE_SUPER_ADMIN));
        TABLE.get(BookingStatus.CONFIRMED).put(BookingTransitionEvent.CUSTOMER_CANCEL,
            new Rule(BookingStatus.CANCELLED, BookingEventType.CANCELLED, false,
                ROLE_CUSTOMER));
        TABLE.get(BookingStatus.CONFIRMED).put(BookingTransitionEvent.ADMIN_CANCEL,
            new Rule(BookingStatus.CANCELLED, BookingEventType.CANCELLED, false,
                ROLE_ADMIN, ROLE_SUPER_ADMIN));
        TABLE.get(BookingStatus.CONFIRMED).put(BookingTransitionEvent.SYSTEM_AUTO_CANCEL,
            new Rule(BookingStatus.CANCELLED, BookingEventType.CANCELLED, false,
                ROLE_SYSTEM));
        TABLE.get(BookingStatus.CONFIRMED).put(BookingTransitionEvent.MARK_NO_SHOW,
            new Rule(BookingStatus.NO_SHOW, BookingEventType.NO_SHOW, false,
                ROLE_SYSTEM));

        // ── CHECKED_IN ──
        TABLE.get(BookingStatus.CHECKED_IN).put(BookingTransitionEvent.CHECK_OUT,
            new Rule(BookingStatus.COMPLETED, BookingEventType.COMPLETED, false,
                ROLE_ADMIN, ROLE_SUPER_ADMIN, ROLE_SYSTEM));
        TABLE.get(BookingStatus.CHECKED_IN).put(BookingTransitionEvent.UNDO_CHECK_IN,
            new Rule(BookingStatus.CONFIRMED, BookingEventType.CHECK_IN_REVERTED, true,
                ROLE_ADMIN, ROLE_SUPER_ADMIN));

        // COMPLETED / CANCELLED / NO_SHOW are terminal — no normal transitions.
        // Use override(...) for super-admin recovery.
    }

    /** Override targets allowed only from terminal states for SUPER_ADMIN recovery. */
    private static final Map<BookingStatus, Set<BookingStatus>> OVERRIDE_TARGETS = Map.of(
        BookingStatus.CANCELLED,  EnumSet.of(BookingStatus.CONFIRMED, BookingStatus.PENDING),
        BookingStatus.NO_SHOW,    EnumSet.of(BookingStatus.CHECKED_IN, BookingStatus.CONFIRMED),
        BookingStatus.COMPLETED,  EnumSet.of(BookingStatus.CHECKED_IN)
    );

    // ─── Public API ─────────────────────────────────────────────────────────

    /**
     * Apply an event-driven transition. Returns the persisted booking with
     * its new status. The booking's {@code status} field is mutated in place
     * AND saved through {@link BookingRepository}, and an audit event is
     * emitted to {@link BookingEventLogService}.
     *
     * @param booking     entity whose status will be advanced
     * @param event       domain event triggering the transition
     * @param actor       who is performing the transition (role-checked)
     * @param reason      free-text rationale (required for some transitions)
     * @return            the persisted booking after the transition
     * @throws InvalidTransitionException if the rule is missing, the actor's
     *         role is not permitted, or a required reason is blank
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public Booking transition(Booking booking, BookingTransitionEvent event,
                              TransitionActor actor, String reason) {
        if (booking == null) {
            throw new IllegalArgumentException("booking must not be null");
        }
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (actor == null) actor = TransitionActor.system();

        BookingStatus from = booking.getStatus();
        Rule rule = TABLE.get(from).get(event);

        if (rule == null) {
            // Idempotency: if the event would have been a no-op transition to
            // the SAME state, treat it as already-applied. We can detect this
            // by checking whether ANY rule for this event lands on the
            // current status (i.e. another path with same end state).
            if (isAlreadyInTargetForEvent(from, event)) {
                log.debug("State machine no-op: booking {} already in {} for event {}",
                    booking.getBookingRef(), from, event);
                return booking;
            }
            throw new InvalidTransitionException(from, event, actor.getRole(),
                "no rule defined for this (status, event) pair");
        }

        if (!rule.allowedRoles.contains(actor.getRole())) {
            throw new InvalidTransitionException(from, event, actor.getRole(),
                "role not permitted; allowed=" + rule.allowedRoles);
        }

        if (rule.reasonRequired && (reason == null || reason.isBlank())) {
            throw new InvalidTransitionException(from, event, actor.getRole(),
                "reason is required for this transition");
        }

        return applyAndAudit(booking, from, rule.to, rule.auditEvent, actor, reason,
            /*isOverride=*/false);
    }

    /**
     * Super-admin override path. Forces a transition that the normal
     * transition table does NOT allow — used only for operational recovery
     * (e.g., reinstate a wrongly-cancelled booking, undo an erroneous
     * no-show). Reason is mandatory and the audit event is recorded as
     * {@link BookingEventType#MANUAL_REVIEW_FLAGGED} so it stands out in the
     * timeline.
     *
     * @throws InvalidTransitionException if actor is not SUPER_ADMIN, reason
     *         is blank, or the (from, target) pair is not in the override
     *         allow-list.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public Booking override(Booking booking, BookingStatus target,
                            TransitionActor actor, String reason) {
        if (booking == null) {
            throw new IllegalArgumentException("booking must not be null");
        }
        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }
        if (actor == null || !actor.isSuperAdmin()) {
            throw new InvalidTransitionException(
                booking.getStatus(), BookingTransitionEvent.ADMIN_OVERRIDE,
                actor == null ? "null" : actor.getRole(),
                "override requires SUPER_ADMIN role");
        }
        if (reason == null || reason.isBlank()) {
            throw new InvalidTransitionException(
                booking.getStatus(), BookingTransitionEvent.ADMIN_OVERRIDE,
                actor.getRole(),
                "override reason is mandatory");
        }

        BookingStatus from = booking.getStatus();
        Set<BookingStatus> allowed = OVERRIDE_TARGETS.getOrDefault(from, Set.of());
        if (!allowed.contains(target)) {
            throw new InvalidTransitionException(from,
                BookingTransitionEvent.ADMIN_OVERRIDE, actor.getRole(),
                "override " + from + " → " + target
                    + " is not in the allow-list; allowed targets from "
                    + from + " = " + allowed);
        }

        log.warn("STATE_MACHINE_OVERRIDE: booking {} forced {} → {} by SUPER_ADMIN id={} reason='{}'",
            booking.getBookingRef(), from, target, actor.getUserId(), reason);

        return applyAndAudit(booking, from, target,
            BookingEventType.MANUAL_REVIEW_FLAGGED, actor, reason,
            /*isOverride=*/true);
    }

    /**
     * Inspect-only: whether the {@code (from, event)} pair has a defined
     * transition rule. Use in controllers/services to short-circuit work
     * before throwing — e.g. dashboards rendering allowed-action buttons.
     */
    public boolean canTransition(BookingStatus from, BookingTransitionEvent event,
                                 TransitionActor actor) {
        if (from == null || event == null || actor == null) return false;
        Rule rule = TABLE.get(from).get(event);
        return rule != null && rule.allowedRoles.contains(actor.getRole());
    }

    // ─── Internals ──────────────────────────────────────────────────────────

    private Booking applyAndAudit(Booking booking, BookingStatus from, BookingStatus to,
                                  BookingEventType auditEvent, TransitionActor actor,
                                  String reason, boolean isOverride) {

        // Idempotent no-op: same status, same event → don't re-emit audit row.
        if (from == to) {
            log.debug("State machine no-op: booking {} already in {}",
                booking.getBookingRef(), to);
            return booking;
        }

        booking.setStatus(to);
        Booking saved = bookingRepository.save(booking);

        String description = buildDescription(from, to, actor, reason, isOverride);

        eventLogService.logEventFull(
            saved,
            auditEvent,
            from.name(),
            actor.getUserId(),
            actor.getRole(),
            actor.getDisplayName(),
            description,
            reason,
            RequestContext.currentIp(),
            RequestContext.currentUserAgent()
        );

        log.info("BOOKING_TRANSITION ref={} {} → {} actor={}/{} override={} reason='{}'",
            saved.getBookingRef(), from, to, actor.getRole(), actor.getUserId(),
            isOverride, reason == null ? "" : reason);

        return saved;
    }

    private static String buildDescription(BookingStatus from, BookingStatus to,
                                           TransitionActor actor, String reason,
                                           boolean isOverride) {
        StringBuilder sb = new StringBuilder()
            .append(isOverride ? "[OVERRIDE] " : "")
            .append(from).append(" → ").append(to)
            .append(" by ").append(actor.getRole());
        if (actor.getDisplayName() != null && !actor.getDisplayName().isBlank()) {
            sb.append(" (").append(actor.getDisplayName()).append(")");
        }
        if (reason != null && !reason.isBlank()) {
            sb.append(": ").append(reason.trim());
        }
        return sb.toString();
    }

    /**
     * If a Kafka event is replayed after the booking has already been
     * advanced past the event's target, treat it as already-applied rather
     * than throwing. We only no-op when the booking is in the EXACT target
     * state for that event (e.g. PAYMENT_SUCCEEDED replayed on already
     * CONFIRMED) — never silently swallow other terminal mismatches.
     */
    private static boolean isAlreadyInTargetForEvent(BookingStatus current,
                                                     BookingTransitionEvent event) {
        return switch (event) {
            case PAYMENT_SUCCEEDED, ADMIN_CONFIRM ->
                current == BookingStatus.CONFIRMED;
            case CUSTOMER_CANCEL, ADMIN_CANCEL, SYSTEM_AUTO_CANCEL ->
                current == BookingStatus.CANCELLED;
            case CHECK_IN ->
                current == BookingStatus.CHECKED_IN;
            case CHECK_OUT ->
                current == BookingStatus.COMPLETED;
            case MARK_NO_SHOW ->
                current == BookingStatus.NO_SHOW;
            default -> false;
        };
    }
}
