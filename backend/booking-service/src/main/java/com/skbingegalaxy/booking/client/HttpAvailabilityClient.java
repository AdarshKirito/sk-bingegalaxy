package com.skbingegalaxy.booking.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.LocalDate;

@Service
@Slf4j
@Primary
public class HttpAvailabilityClient implements AvailabilityClient {

    private final AvailabilityClientFallback fallback;
    private final RestClient restClient;

    @Value("${availability.service.url:${AVAILABILITY_SERVICE_URL:http://availability-service:8082}}")
    private String availabilityServiceUrl;

    public HttpAvailabilityClient(AvailabilityClientFallback fallback, RestClient.Builder restClientBuilder) {
        this.fallback = fallback;
        this.restClient = restClientBuilder.build();
    }

    @Override
    public Boolean checkSlotAvailable(String internalApiSecret,
                                      LocalDate date,
                                      Long bingeId,
                                      int startMinute,
                                      int durationMinutes) {
        URI uri = UriComponentsBuilder.fromHttpUrl(availabilityServiceUrl)
            .path("/api/v1/availability/internal/check")
            .queryParam("date", date)
            .queryParam("bingeId", bingeId)
            .queryParam("startMinute", startMinute)
            .queryParam("durationMinutes", durationMinutes)
            .build(true)
            .toUri();

        try {
            return restClient
                .get()
                .uri(uri)
                .header("X-Internal-Secret", internalApiSecret)
                .retrieve()
                .body(Boolean.class);
        } catch (RestClientException ex) {
            log.warn("Availability check failed for date={}, bingeId={}, startMinute={}, durationMinutes={}: {}",
                date, bingeId, startMinute, durationMinutes, ex.getMessage());
            return fallback.checkSlotAvailable(internalApiSecret, date, bingeId, startMinute, durationMinutes);
        }
    }
}