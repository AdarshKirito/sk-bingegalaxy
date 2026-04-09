package com.skbingegalaxy.booking.client;

import java.time.LocalDate;

public interface AvailabilityClient {

    Boolean checkSlotAvailable(
        String internalApiSecret,
        LocalDate date,
        Long bingeId,
        int startMinute,
        int durationMinutes
    );
}
