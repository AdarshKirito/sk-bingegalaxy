package com.skbingegalaxy.booking.listener;

import com.skbingegalaxy.booking.entity.ProcessedEvent;
import com.skbingegalaxy.booking.repository.ProcessedEventRepository;
import com.skbingegalaxy.booking.service.WaitlistService;
import com.skbingegalaxy.common.constants.KafkaTopics;
import com.skbingegalaxy.common.event.BookingEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class WaitlistPromotionListener {

    private final WaitlistService waitlistService;
    private final ProcessedEventRepository processedEventRepository;

    @KafkaListener(topics = KafkaTopics.BOOKING_CANCELLED, groupId = "booking-waitlist-group")
    @Transactional
    public void onBookingCancelled(BookingEvent event) {
        String key = "WAITLIST_PROMOTION:" + event.getBookingRef();
        if (processedEventRepository.existsByEventKey(key)) {
            log.debug("Duplicate waitlist promotion event skipped: {}", event.getBookingRef());
            return;
        }

        try {
            if (event.getBookingDate() != null) {
                log.info("Checking waitlist promotion after cancellation of booking {} on {}",
                    event.getBookingRef(), event.getBookingDate());
                waitlistService.promoteWaitlistAfterCancellation(event);
            }
            processedEventRepository.save(ProcessedEvent.builder().eventKey(key).build());
        } catch (Exception e) {
            log.error("Failed to process waitlist promotion for booking {}", event.getBookingRef(), e);
            throw e; // Re-throw so DefaultErrorHandler retries / sends to DLT
        }
    }
}
