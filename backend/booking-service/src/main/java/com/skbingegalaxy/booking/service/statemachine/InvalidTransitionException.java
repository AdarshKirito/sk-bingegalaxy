package com.skbingegalaxy.booking.service.statemachine;

import com.skbingegalaxy.common.enums.BookingStatus;
import com.skbingegalaxy.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * Thrown by {@link BookingStateMachine} when a transition is rejected — either
 * because the {@code (currentStatus, event)} pair has no rule, or because the
 * actor's role is not permitted, or because a required reason was not
 * supplied. Always 409 Conflict so clients can distinguish it from generic
 * 400 validation errors.
 */
public class InvalidTransitionException extends BusinessException {

    private final BookingStatus from;
    private final BookingTransitionEvent event;
    private final String actorRole;

    public InvalidTransitionException(BookingStatus from, BookingTransitionEvent event,
                                      String actorRole, String detail) {
        super(buildMessage(from, event, actorRole, detail), HttpStatus.CONFLICT);
        this.from = from;
        this.event = event;
        this.actorRole = actorRole;
    }

    private static String buildMessage(BookingStatus from, BookingTransitionEvent event,
                                       String actorRole, String detail) {
        return "Cannot transition booking from " + from
            + " via " + event
            + " (actor=" + actorRole + ")"
            + (detail != null && !detail.isBlank() ? " — " + detail : "");
    }

    public BookingStatus getFrom() { return from; }
    public BookingTransitionEvent getEvent() { return event; }
    public String getActorRole() { return actorRole; }
}
