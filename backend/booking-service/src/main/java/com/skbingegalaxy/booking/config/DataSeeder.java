package com.skbingegalaxy.booking.config;

import com.skbingegalaxy.booking.entity.*;
import com.skbingegalaxy.booking.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Seeds a default Binge (venue) with event types, add-ons, venue rooms,
 * and cancellation tiers so the platform is usable out of the box.
 * Idempotent: skips seeding when data already exists.
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements CommandLineRunner {

    private final BingeRepository bingeRepository;
    private final EventTypeRepository eventTypeRepository;
    private final AddOnRepository addOnRepository;
    private final AddOnCategoryRepository addOnCategoryRepository;
    private final VenueRoomRepository venueRoomRepository;
    private final CancellationTierRepository cancellationTierRepository;

    @Override
    @Transactional
    public void run(String... args) {
        Binge binge = seedDefaultBinge();
        if (binge == null) return;          // already existed → nothing to do
        seedEventTypes(binge.getId());
        seedAddOns(binge.getId());
        seedVenueRooms(binge.getId());
        seedCancellationTiers(binge.getId());
    }

    /* ── Binge ─────────────────────────────────────────────── */

    private static final String DEFAULT_BINGE_NAME = "SK Binge Galaxy \u2014 Main";

    private Binge seedDefaultBinge() {
        if (bingeRepository.existsByNameAndAdminId(DEFAULT_BINGE_NAME, 1L)) {
            log.info("DataSeeder: default binge already exists — skipping all seed data");
            return null;
        }

        Binge binge = Binge.builder()
                .name(DEFAULT_BINGE_NAME)
                .address("Hyderabad, Telangana, India")
                .city("Hyderabad")
                .state("Telangana")
                .country("IN")
                // Geocoded (Hyderabad city centre) so the "venues near me" proximity
                // discovery has something to rank in a fresh out-of-the-box install.
                .latitude(17.3850)
                .longitude(78.4867)
                .adminId(1L)                                // super-admin (first user seeded by auth-service)
                .active(true)
                .operationalDate(LocalDate.now())
                .supportEmail("support@skbingegalaxy.com")
                .supportPhone("9876543210")
                .supportPhoneCountryCode("+91")
                .supportWhatsapp("9876543210")
                .supportWhatsappCountryCode("+91")
                .customerCancellationEnabled(true)
                .customerCancellationCutoffMinutes(180)
                .openTime(LocalTime.of(10, 0))
                .closeTime(LocalTime.of(23, 0))
                .maxConcurrentBookings(3)
                .build();
        binge = bingeRepository.save(binge);
        log.info("Seeded default binge: id={}, name={}", binge.getId(), binge.getName());
        return binge;
    }

    /* ── Event Types ───────────────────────────────────────── */

    private void seedEventTypes(Long bingeId) {
        if (eventTypeRepository.existsByBingeId(bingeId)) return;

        List<EventType> types = List.of(
            EventType.builder().bingeId(bingeId).name("Birthday Celebration").description("Private theater birthday party with decorations").basePrice(new BigDecimal("2999")).hourlyRate(new BigDecimal("500")).minHours(2).maxHours(6).build(),
            EventType.builder().bingeId(bingeId).name("Anniversary Special").description("Romantic anniversary celebration setup").basePrice(new BigDecimal("3499")).hourlyRate(new BigDecimal("600")).minHours(2).maxHours(5).build(),
            EventType.builder().bingeId(bingeId).name("Surprise Proposal").description("Elegant proposal setup with premium decorations").basePrice(new BigDecimal("4999")).hourlyRate(new BigDecimal("700")).minHours(2).maxHours(4).build(),
            EventType.builder().bingeId(bingeId).name("HD Screening").description("Private HD movie screening experience").basePrice(new BigDecimal("1999")).hourlyRate(new BigDecimal("400")).minHours(2).maxHours(6).build(),
            EventType.builder().bingeId(bingeId).name("Corporate Event").description("Professional corporate meeting or presentation").basePrice(new BigDecimal("3999")).hourlyRate(new BigDecimal("800")).minHours(2).maxHours(8).build(),
            EventType.builder().bingeId(bingeId).name("Baby Shower").description("Themed baby shower celebration").basePrice(new BigDecimal("3499")).hourlyRate(new BigDecimal("500")).minHours(2).maxHours(5).build(),
            EventType.builder().bingeId(bingeId).name("Custom Event").description("Create your own custom event experience").basePrice(new BigDecimal("2499")).hourlyRate(new BigDecimal("500")).minHours(1).maxHours(8).build()
        );
        eventTypeRepository.saveAll(types);
        log.info("Seeded {} event types for binge {}", types.size(), bingeId);
    }

    /* ── Add-Ons ───────────────────────────────────────────── */

    private void seedAddOns(Long bingeId) {
        if (addOnRepository.existsByBingeId(bingeId)) return;

        // V58: legacy free-text category column is gone — seed categories first
        // and reference them by id. Helper resolves (creating if missing) by
        // name so re-seeding into a partially-populated env stays idempotent.
        java.util.Map<String, Long> catId = new java.util.HashMap<>();
        java.util.function.Function<String, Long> cat = name ->
            catId.computeIfAbsent(name, n -> resolveOrCreateAddOnCategory(bingeId, n));

        List<AddOn> addOns = List.of(
            AddOn.builder().bingeId(bingeId).name("Basic Decoration").description("Balloons and ribbons").price(new BigDecimal("499")).categoryId(cat.apply("DECORATION")).build(),
            AddOn.builder().bingeId(bingeId).name("Premium Decoration").description("Premium themed decoration with LED lights").price(new BigDecimal("1499")).categoryId(cat.apply("DECORATION")).build(),
            AddOn.builder().bingeId(bingeId).name("Flower Decoration").description("Fresh flower arrangements").price(new BigDecimal("999")).categoryId(cat.apply("DECORATION")).build(),
            AddOn.builder().bingeId(bingeId).name("Soft Drinks Pack").description("6 assorted cold drinks").price(new BigDecimal("299")).categoryId(cat.apply("BEVERAGE")).build(),
            AddOn.builder().bingeId(bingeId).name("Premium Beverage Pack").description("Mocktails and fresh juices").price(new BigDecimal("599")).categoryId(cat.apply("BEVERAGE")).build(),
            AddOn.builder().bingeId(bingeId).name("Photo Shoot (30 min)").description("Professional photography session").price(new BigDecimal("1999")).categoryId(cat.apply("PHOTOGRAPHY")).build(),
            AddOn.builder().bingeId(bingeId).name("Photo + Video Shoot").description("Photos and cinematic video coverage").price(new BigDecimal("3999")).categoryId(cat.apply("PHOTOGRAPHY")).build(),
            AddOn.builder().bingeId(bingeId).name("Fog Effect").description("Dramatic fog machine effects").price(new BigDecimal("799")).categoryId(cat.apply("EFFECT")).build(),
            AddOn.builder().bingeId(bingeId).name("Red Carpet Entry").description("VIP red carpet welcome").price(new BigDecimal("999")).categoryId(cat.apply("EFFECT")).build(),
            AddOn.builder().bingeId(bingeId).name("Confetti Blast").description("Confetti cannon celebration").price(new BigDecimal("499")).categoryId(cat.apply("EFFECT")).build(),
            AddOn.builder().bingeId(bingeId).name("Birthday Cake (1 kg)").description("Custom designer cake").price(new BigDecimal("799")).categoryId(cat.apply("FOOD")).build(),
            AddOn.builder().bingeId(bingeId).name("Premium Cake (2 kg)").description("Premium multi-tier designer cake").price(new BigDecimal("1499")).categoryId(cat.apply("FOOD")).build(),
            AddOn.builder().bingeId(bingeId).name("Snacks Platter").description("Assorted finger food and snacks").price(new BigDecimal("699")).categoryId(cat.apply("FOOD")).build(),
            AddOn.builder().bingeId(bingeId).name("Live Music (1 hour)").description("Acoustic live performance").price(new BigDecimal("2999")).categoryId(cat.apply("EXPERIENCE")).build()
        );
        addOnRepository.saveAll(addOns);
        log.info("Seeded {} add-ons for binge {}", addOns.size(), bingeId);
    }

    /** Look up or create an addon_categories row scoped to this binge. */
    private Long resolveOrCreateAddOnCategory(Long bingeId, String name) {
        return addOnCategoryRepository.findByBingeId(bingeId).stream()
            .filter(c -> name.equalsIgnoreCase(c.getName()))
            .findFirst()
            .map(AddOnCategory::getId)
            .orElseGet(() -> {
                AddOnCategory created = AddOnCategory.builder()
                    .bingeId(bingeId)
                    .name(name)
                    .sortOrder(0)
                    .active(true)
                    .build();
                return addOnCategoryRepository.save(created).getId();
            });
    }

    /* ── Venue Rooms ───────────────────────────────────────── */

    private void seedVenueRooms(Long bingeId) {
        if (!venueRoomRepository.findByBingeIdOrderBySortOrderAsc(bingeId).isEmpty()) return;

        List<VenueRoom> rooms = List.of(
            VenueRoom.builder().bingeId(bingeId).name("Galaxy Hall").roomType("MAIN_HALL").capacity(25).description("Main screening hall with 4K projector and surround sound").sortOrder(1).build(),
            VenueRoom.builder().bingeId(bingeId).name("Star Lounge").roomType("PRIVATE_ROOM").capacity(12).description("Intimate private room for small celebrations").sortOrder(2).build(),
            VenueRoom.builder().bingeId(bingeId).name("Nebula VIP").roomType("VIP_LOUNGE").capacity(8).description("Premium VIP lounge with recliner seating").sortOrder(3).build()
        );
        venueRoomRepository.saveAll(rooms);
        log.info("Seeded {} venue rooms for binge {}", rooms.size(), bingeId);
    }

    /* ── Cancellation Tiers ────────────────────────────────── */

    private void seedCancellationTiers(Long bingeId) {
        if (cancellationTierRepository.existsByBingeId(bingeId)) return;

        List<CancellationTier> tiers = List.of(
            CancellationTier.builder().bingeId(bingeId).hoursBeforeStart(48).refundPercentage(100).label("Full refund").build(),
            CancellationTier.builder().bingeId(bingeId).hoursBeforeStart(24).refundPercentage(50).label("Half refund").build(),
            CancellationTier.builder().bingeId(bingeId).hoursBeforeStart(0).refundPercentage(0).label("No refund").build()
        );
        cancellationTierRepository.saveAll(tiers);
        log.info("Seeded {} cancellation tiers for binge {}", tiers.size(), bingeId);
    }
}
