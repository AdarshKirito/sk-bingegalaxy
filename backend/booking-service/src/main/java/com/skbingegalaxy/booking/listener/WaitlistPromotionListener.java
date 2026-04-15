package com.skbingegalaxy.booking.listener;

import com.skbingegalaxy.booking.service.WaitlistService;
import com.skbingegalaxy.common.constants.KafkaTopics;
import com.skbingegalaxy.common.event.BookingEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class WaitlistPromotionListener {

    private final WaitlistService waitlistService;

    @KafkaListener(topics = KafkaTopics.BOOKING_CANCELLED, groupId = "booking-waitlist-group")
    public void onBookingCancelled(BookingEvent event) {
        try {
            if (event.getBookingDate() != null) {
                log.info("Checking waitlist promotion after cancellation of booking {} on {}",
                    event.getBookingRef(), event.getBookingDate());
                // Note: BingeContext is not set from Kafka listeners,
                // so WaitlistService.promoteWaitlistOnCancellation takes bingeId directly
                // We need the bingeId from the event — add it to BookingEvent
                // For now, we extract it from the booking ref via the service
                waitlistService.promoteWaitlistAfterCancellation(event);
            }
        } catch (Exception e) {
            log.error("Failed to process waitlist promotion for booking {}", event.getBookingRef(), e);
        }
    }
}
