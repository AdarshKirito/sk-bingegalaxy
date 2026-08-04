package com.skbingegalaxy.distribution;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Distribution bounded context.
 *
 * <p><b>What this service owns:</b> connections to connectivity providers, the sales
 * destinations those connections reach, listing mappings and their readiness, the
 * inbound reservation inbox, settlements, sync state and provider health.
 *
 * <p><b>What it must never own</b> — the invariant the whole design rests on:
 * <ul>
 *   <li><b>No inventory truth.</b> Availability lives in availability-service and
 *       booking-service. A cached copy here would become a second inventory truth and
 *       reintroduce exactly the oversell class the V81 database backstop exists to
 *       prevent.</li>
 *   <li><b>No booking truth.</b> Reservations are canonical in booking-service. This
 *       service stores a {@code booking_ref} and nothing more.</li>
 *   <li><b>No pricing truth.</b> Prices resolve through the existing rate-code →
 *       surge → FX → tax pipeline. Distribution reads resolved prices; it never
 *       computes one.</li>
 * </ul>
 *
 * <p><b>Why it is a separate service</b> rather than a package in booking-service:
 * a different trust boundary (reseller traffic is machine-to-machine, not user JWT),
 * a different failure posture (a provider outage must never degrade direct booking),
 * and blast radius — {@code BookingService} is already 5,000+ lines and the audit
 * names it the main source of regressions.
 *
 * <p>See {@code docs/distribution/05-DISTRIBUTION-CONSOLE-DESIGN-V2.md}.
 */
/*
 * NOTE — scheduling is deliberately NOT enabled yet.
 *
 * This service will need it (feed staleness sweeps, credential-expiry warnings), but
 * `@EnableScheduling` on its own is a trap here: every replica would run every
 * `@Scheduled` method, so a staleness sweep would send duplicate warnings and a feed
 * publish would run concurrently with itself. booking-service solves this with ShedLock
 * (`@SchedulerLock`, backed by the `shedlock` table). Enable scheduling in the SAME
 * change that adds ShedLock and the first job — not before, so the annotation cannot
 * sit here inviting an unguarded `@Scheduled`.
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
public class DistributionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DistributionServiceApplication.class, args);
    }
}
