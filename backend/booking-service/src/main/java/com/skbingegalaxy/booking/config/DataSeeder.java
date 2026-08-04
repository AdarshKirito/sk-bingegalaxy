package com.skbingegalaxy.booking.config;

import com.skbingegalaxy.booking.entity.*;
import com.skbingegalaxy.booking.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Seeds the bookable catalog — event types, add-ons, venue rooms, cancellation
 * tiers and their photo galleries — so the platform is usable out of the box.
 *
 * <p>Two behaviours worth knowing about:
 * <ul>
 *   <li><b>Every binge, not just the default one.</b> A brand-new binge created
 *       through the admin UI starts with an empty catalog, which makes the
 *       booking wizard a dead end. Each boot tops up every binge with any
 *       catalog entry it is missing, so new venues become bookable immediately.</li>
 *   <li><b>Additive, never destructive.</b> Entries are matched by name, so an
 *       item an admin renamed, repriced or deleted is not resurrected or
 *       overwritten — only genuinely absent names are inserted. Photos are
 *       attached only to rows that currently have none, so a curated gallery
 *       is never clobbered.</li>
 * </ul>
 *
 * <p>Set {@code app.seed.catalog-all-binges=false} to restrict seeding to a
 * freshly created default binge (the pre-existing behaviour) — useful for an
 * environment where admins fully curate their own catalogs.
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements CommandLineRunner {

    private final BingeRepository bingeRepository;
    private final EventTypeRepository eventTypeRepository;
    private final EventCategoryRepository eventCategoryRepository;
    private final AddOnRepository addOnRepository;
    private final AddOnCategoryRepository addOnCategoryRepository;
    private final VenueRoomRepository venueRoomRepository;
    private final CancellationTierRepository cancellationTierRepository;
    // Seeding a catalogue makes a binge operational, and only BingeService may record
    // that. Writing event_types without telling it is what auto-paused every seeded
    // venue 24 hours after approval — see seedEventTypes below.
    private final com.skbingegalaxy.booking.service.BingeService bingeService;

    @Value("${app.seed.catalog-all-binges:true}")
    private boolean seedAllBinges;

    @Override
    @Transactional
    public void run(String... args) {
        Binge created = seedDefaultBinge();

        // Sorted by id, and the ordering is a concurrency guard rather than cosmetics.
        //
        // This runs on EVERY replica at boot (a CommandLineRunner, not ShedLock-guarded),
        // and since V87 each event_types INSERT takes a row lock on its parent binge via
        // the first-event trigger. Two replicas seeding the same binges in DIFFERENT
        // orders could therefore deadlock. `findAll()` gives no order guarantee — worse,
        // PostgreSQL's synchronize_seqscans lets concurrent sequential scans start at
        // different points in the table, so two nodes genuinely can disagree.
        //
        // A deterministic order makes the replicas queue behind each other instead of
        // deadlocking, which is the difference between slow and broken.
        List<Binge> targets = seedAllBinges
            ? bingeRepository.findAll().stream()
                .sorted(java.util.Comparator.comparing(Binge::getId))
                .toList()
            : (created == null ? List.of() : List.of(created));

        for (Binge binge : targets) {
            Long id = binge.getId();
            seedEventTypes(id);
            seedAddOns(id);
            seedVenueRooms(id);
            seedCancellationTiers(id);
        }
    }

    /* ── Binge ─────────────────────────────────────────────── */

    private static final String DEFAULT_BINGE_NAME = "SK Binge Galaxy — Main";

    /** @return the binge if it was created by this run, or {@code null} if it already existed. */
    private Binge seedDefaultBinge() {
        if (bingeRepository.existsByNameAndAdminId(DEFAULT_BINGE_NAME, 1L)) {
            log.info("DataSeeder: default binge already exists — catalog top-up only");
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

    /* ── Photo galleries ───────────────────────────────────── */

    /**
     * Unsplash CDN stills, addressed by permanent photo id. Every id below was
     * checked to resolve 200 before being committed — a dead id would render as
     * the "No image" placeholder in the wizard. {@code img-src https:} is already
     * allowed by the gateway/nginx CSP, so no policy change is needed.
     */
    private static String photo(String id) {
        return "https://images.unsplash.com/" + id + "?auto=format&fit=crop&w=900&q=80";
    }

    private static List<String> gallery(String... ids) {
        List<String> urls = new ArrayList<>(ids.length);
        for (String id : ids) urls.add(photo(id));
        return List.copyOf(urls);
    }

    private static final List<String> PIC_BIRTHDAY = gallery(
        "photo-1530103862676-de8c9debad1d", "photo-1513151233558-d860c5398176",
        "photo-1464349095431-e9a21285b5f3", "photo-1478146896981-b80fe463b330");
    private static final List<String> PIC_PARTY = gallery(
        "photo-1496843916299-590492c751f4", "photo-1533228100845-08145b01de14",
        "photo-1516450360452-9312f5e86fc7");
    private static final List<String> PIC_ANNIVERSARY = gallery(
        "photo-1518199266791-5375a83190b7", "photo-1522673607200-164d1b6ce486",
        "photo-1519741497674-611481863552");
    private static final List<String> PIC_PROPOSAL = gallery(
        "photo-1591604466107-ec97de577aff", "photo-1520854221256-17451cc331bf",
        "photo-1465495976277-4387d4b0b4c6");
    private static final List<String> PIC_CINEMA = gallery(
        "photo-1517604931442-7e0c8ed2963c", "photo-1489599849927-2ee91cede3ba",
        "photo-1478720568477-152d9b164e26", "photo-1536440136628-849c177e76a1");
    private static final List<String> PIC_CORPORATE = gallery(
        "photo-1540575467063-178a50c2df87", "photo-1517245386807-bb43f82c33c4",
        "photo-1505373877841-8d25f7d46678");
    private static final List<String> PIC_BABY = gallery(
        "photo-1519689680058-324335c77eba", "photo-1555252333-9f8e92e65df9");
    private static final List<String> PIC_DECOR = gallery(
        "photo-1527529482837-4698179dc6ce", "photo-1492684223066-81342ee5ff30",
        "photo-1467810563316-b5476525c0f9", "photo-1478147427282-58a87a120781");
    private static final List<String> PIC_FLOWERS = gallery(
        "photo-1490750967868-88aa4486c946", "photo-1487070183336-b863922373d4",
        "photo-1509587584298-0f3b3a3a1797");
    private static final List<String> PIC_BEVERAGE = gallery(
        "photo-1544145945-f90425340c7e", "photo-1513558161293-cdaf765ed2fd",
        "photo-1551024709-8f23befc6f87");
    private static final List<String> PIC_PHOTOGRAPHY = gallery(
        "photo-1502920917128-1aa500764cbd", "photo-1516035069371-29a1b244cc32",
        "photo-1554048612-b6a482bc67e5");
    private static final List<String> PIC_EFFECTS = gallery(
        "photo-1514525253161-7a46d19cd819", "photo-1508997449629-303059a039c0",
        "photo-1519671482749-fd09be7ccebf");
    private static final List<String> PIC_SPARKLE = gallery(
        "photo-1544427920-c49ccfb85579", "photo-1541532713592-79a0317b6b77",
        "photo-1533228100845-08145b01de14");
    private static final List<String> PIC_CAKE = gallery(
        "photo-1578985545062-69928b1d9587", "photo-1535141192574-5d4897c12636",
        "photo-1464349095431-e9a21285b5f3");
    private static final List<String> PIC_FOOD = gallery(
        "photo-1555939594-58d7cb561ad1", "photo-1414235077428-338989a2e8c0",
        "photo-1504674900247-0877df9cc836");
    private static final List<String> PIC_MUSIC = gallery(
        "photo-1470225620780-dba8ba36b745", "photo-1493225457124-a3eb161ffa5f",
        "photo-1511671782779-c97d3d27a1d4");
    private static final List<String> PIC_HALL = gallery(
        "photo-1571003123894-1f0594d2b5d9", "photo-1522708323590-d24dbb6b0267",
        "photo-1618221195710-dd6b41faaea6");
    private static final List<String> PIC_LOUNGE = gallery(
        "photo-1586023492125-27b2c045efd7", "photo-1567767292278-a4f21aa2d36e",
        "photo-1560448204-e02f11c3d0e2");
    private static final List<String> PIC_VIP = gallery(
        "photo-1616486338812-3dadae4b4ace", "photo-1600210492486-724fe5c67fb0",
        "photo-1600607687939-ce8a6c25118c");

    /* ── Event Types ───────────────────────────────────────── */

    private record EventSpec(String name, String description, String basePrice, String hourlyRate,
                             int minHours, int maxHours, String category, List<String> images) {}

    private static final List<EventSpec> EVENT_CATALOG = List.of(
        new EventSpec("Birthday Celebration", "Private theater birthday party with decorations", "2999", "500", 2, 6, "Celebrations", PIC_BIRTHDAY),
        new EventSpec("Anniversary Special", "Romantic anniversary celebration setup", "3499", "600", 2, 5, "Romance", PIC_ANNIVERSARY),
        new EventSpec("Surprise Proposal", "Elegant proposal setup with premium decorations", "4999", "700", 2, 4, "Romance", PIC_PROPOSAL),
        new EventSpec("HD Screening", "Private HD movie screening experience", "1999", "400", 2, 6, "Screenings", PIC_CINEMA),
        new EventSpec("Corporate Event", "Professional corporate meeting or presentation", "3999", "800", 2, 8, "Corporate", PIC_CORPORATE),
        new EventSpec("Baby Shower", "Themed baby shower celebration", "3499", "500", 2, 5, "Kids & Family", PIC_BABY),
        new EventSpec("Custom Event", "Create your own custom event experience", "2499", "500", 1, 8, null, PIC_PARTY),
        // ── added so every binge opens with a fuller, browsable catalog ──
        new EventSpec("Bachelorette Party", "Private bash for the bride-to-be with neon decor and music", "4499", "650", 3, 6, "Celebrations", PIC_PARTY),
        new EventSpec("Engagement Ceremony", "Ring ceremony setup with floral backdrop and stage lighting", "5499", "750", 3, 6, "Romance", PIC_FLOWERS),
        new EventSpec("Kids Birthday Bash", "Cartoon-themed party with games, balloons and kid-safe seating", "2799", "450", 2, 5, "Kids & Family", PIC_BIRTHDAY),
        new EventSpec("Match Screening", "Live sports on the big screen with stadium-style sound", "1799", "350", 2, 6, "Screenings", PIC_CINEMA),
        new EventSpec("Graduation Party", "Celebrate the class of the year with a photo wall and toast", "3299", "550", 2, 6, "Celebrations", PIC_PARTY),
        new EventSpec("Reunion & Farewell", "Get the group back together for a nostalgic evening", "2999", "500", 2, 8, "Celebrations", PIC_MUSIC)
    );

    private void seedEventTypes(Long bingeId) {
        Map<String, EventType> existing = byName(eventTypeRepository.findByBingeId(bingeId), EventType::getName);
        Map<String, Long> catCache = new HashMap<>();
        Function<String, Long> category = name ->
            name == null ? null : catCache.computeIfAbsent(name, n -> resolveOrCreateEventCategory(bingeId, n));

        List<EventType> dirty = new ArrayList<>();
        int added = 0, photographed = 0;

        for (EventSpec spec : EVENT_CATALOG) {
            EventType row = existing.get(key(spec.name()));
            if (row == null) {
                dirty.add(EventType.builder()
                    .bingeId(bingeId)
                    .name(spec.name())
                    .description(spec.description())
                    .basePrice(new BigDecimal(spec.basePrice()))
                    .hourlyRate(new BigDecimal(spec.hourlyRate()))
                    .minHours(spec.minHours())
                    .maxHours(spec.maxHours())
                    .categoryId(category.apply(spec.category()))
                    .imageUrls(new ArrayList<>(spec.images()))
                    .build());
                added++;
            } else {
                boolean changed = false;
                if (isEmpty(row.getImageUrls())) {
                    row.setImageUrls(new ArrayList<>(spec.images()));
                    photographed++;
                    changed = true;
                }
                // Only fill a category in — never move an event an admin re-filed.
                if (row.getCategoryId() == null && spec.category() != null) {
                    row.setCategoryId(category.apply(spec.category()));
                    changed = true;
                }
                if (changed) dirty.add(row);
            }
        }

        if (!dirty.isEmpty()) {
            eventTypeRepository.saveAll(dirty);
            log.info("Binge {}: seeded {} event types, added photos to {}", bingeId, added, photographed);
        }

        // A binge with a catalogue is operational, and the grace-period sweep must know
        // it. This call was missing: the seeder wrote event_types directly and never
        // stamped binges.first_event_created_at, so BingeGracePeriodScheduler saw a NULL
        // flag, concluded "no events in 24 hours" and set active = false — on venues
        // holding all 13 seeded event types. Called unconditionally because by this
        // point the catalogue exists whether or not THIS run created it, and the stamp
        // is idempotent.
        if (eventTypeRepository.existsByBingeId(bingeId)) {
            bingeService.recordFirstEventIfNeeded(bingeId);
        }
    }

    /** Look up or create an event_categories row scoped to this binge. */
    private Long resolveOrCreateEventCategory(Long bingeId, String name) {
        return eventCategoryRepository.findByBingeId(bingeId).stream()
            .filter(c -> name.equalsIgnoreCase(c.getName()))
            .findFirst()
            .map(EventCategory::getId)
            .orElseGet(() -> eventCategoryRepository.save(EventCategory.builder()
                .bingeId(bingeId)
                .name(name)
                .sortOrder(0)
                .active(true)
                .build()).getId());
    }

    /* ── Add-Ons ───────────────────────────────────────────── */

    private record AddOnSpec(String name, String description, String price,
                             String category, List<String> images) {}

    private static final List<AddOnSpec> ADDON_CATALOG = List.of(
        new AddOnSpec("Basic Decoration", "Balloons and ribbons", "499", "DECORATION", PIC_DECOR),
        new AddOnSpec("Premium Decoration", "Premium themed decoration with LED lights", "1499", "DECORATION", PIC_DECOR),
        new AddOnSpec("Flower Decoration", "Fresh flower arrangements", "999", "DECORATION", PIC_FLOWERS),
        new AddOnSpec("Soft Drinks Pack", "6 assorted cold drinks", "299", "BEVERAGE", PIC_BEVERAGE),
        new AddOnSpec("Premium Beverage Pack", "Mocktails and fresh juices", "599", "BEVERAGE", PIC_BEVERAGE),
        new AddOnSpec("Photo Shoot (30 min)", "Professional photography session", "1999", "PHOTOGRAPHY", PIC_PHOTOGRAPHY),
        new AddOnSpec("Photo + Video Shoot", "Photos and cinematic video coverage", "3999", "PHOTOGRAPHY", PIC_PHOTOGRAPHY),
        new AddOnSpec("Fog Effect", "Dramatic fog machine effects", "799", "EFFECT", PIC_EFFECTS),
        new AddOnSpec("Red Carpet Entry", "VIP red carpet welcome", "999", "EFFECT", PIC_EFFECTS),
        new AddOnSpec("Confetti Blast", "Confetti cannon celebration", "499", "EFFECT", PIC_SPARKLE),
        new AddOnSpec("Birthday Cake (1 kg)", "Custom designer cake", "799", "FOOD", PIC_CAKE),
        new AddOnSpec("Premium Cake (2 kg)", "Premium multi-tier designer cake", "1499", "FOOD", PIC_CAKE),
        new AddOnSpec("Snacks Platter", "Assorted finger food and snacks", "699", "FOOD", PIC_FOOD),
        new AddOnSpec("Live Music (1 hour)", "Acoustic live performance", "2999", "EXPERIENCE", PIC_MUSIC),
        // ── added so the add-ons step has depth in every category ──
        new AddOnSpec("Balloon Arch", "Oversized balloon arch in your chosen palette", "1299", "DECORATION", PIC_DECOR),
        new AddOnSpec("Neon Name Sign", "Custom LED neon sign with your name or message", "1799", "DECORATION", PIC_DECOR),
        new AddOnSpec("Cold Pyro Sparklers", "Indoor-safe cold sparkler fountains for the big moment", "1199", "EFFECT", PIC_SPARKLE),
        new AddOnSpec("Bubble Machine", "Continuous bubble effect through the celebration", "599", "EFFECT", PIC_EFFECTS),
        new AddOnSpec("Mocktail Bar", "Live counter mixing four signature mocktails", "1899", "BEVERAGE", PIC_BEVERAGE),
        new AddOnSpec("Popcorn & Nachos Combo", "Freshly popped corn and loaded nachos for the group", "549", "FOOD", PIC_FOOD),
        new AddOnSpec("Candlelight Dinner Setup", "Private table setting with candles and plated dinner", "2499", "EXPERIENCE", PIC_ANNIVERSARY),
        new AddOnSpec("Karaoke Setup", "Wireless mics, screen lyrics and a 5000-track library", "1599", "EXPERIENCE", PIC_MUSIC),
        new AddOnSpec("Photo Booth with Props", "Instant-print booth with themed props", "2299", "PHOTOGRAPHY", PIC_PHOTOGRAPHY),
        new AddOnSpec("Event Host / Anchor", "Professional anchor to run the evening", "3499", "EXPERIENCE", PIC_MUSIC)
    );

    private void seedAddOns(Long bingeId) {
        Map<String, AddOn> existing = byName(addOnRepository.findByBingeId(bingeId), AddOn::getName);
        Map<String, Long> catCache = new HashMap<>();
        Function<String, Long> category = name ->
            catCache.computeIfAbsent(name, n -> resolveOrCreateAddOnCategory(bingeId, n));

        List<AddOn> dirty = new ArrayList<>();
        int added = 0, photographed = 0;

        for (AddOnSpec spec : ADDON_CATALOG) {
            AddOn row = existing.get(key(spec.name()));
            if (row == null) {
                dirty.add(AddOn.builder()
                    .bingeId(bingeId)
                    .name(spec.name())
                    .description(spec.description())
                    .price(new BigDecimal(spec.price()))
                    .categoryId(category.apply(spec.category()))
                    .imageUrls(new ArrayList<>(spec.images()))
                    .build());
                added++;
            } else if (isEmpty(row.getImageUrls())) {
                row.setImageUrls(new ArrayList<>(spec.images()));
                dirty.add(row);
                photographed++;
            }
        }

        if (!dirty.isEmpty()) {
            addOnRepository.saveAll(dirty);
            log.info("Binge {}: seeded {} add-ons, added photos to {}", bingeId, added, photographed);
        }
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

    private record RoomSpec(String name, String roomType, String description,
                            int sortOrder, String priceAddition, List<String> images) {}

    // Rooms are EXCLUSIVE spaces: capacity 1 = a room hosts one party at a time, so a
    // room that is booked for a given window is unavailable to anyone else for it. A
    // venue WITH rooms is bounded by its bookable room count rather than the static
    // maxConcurrentBookings ceiling (see BookingService#createBooking), so adding a
    // room raises real capacity without any binge-level setting having to change.
    private static final List<RoomSpec> ROOM_CATALOG = List.of(
        new RoomSpec("Galaxy Hall", "MAIN_HALL", "Main screening hall with 4K projector and surround sound", 1, "0", PIC_HALL),
        new RoomSpec("Star Lounge", "PRIVATE_ROOM", "Intimate private room for small celebrations", 2, "0", PIC_LOUNGE),
        new RoomSpec("Nebula VIP", "VIP_LOUNGE", "Premium VIP lounge with recliner seating", 3, "0", PIC_VIP),
        // ── added so "choose a room" is a real choice at every binge ──
        new RoomSpec("Orion Screening Room", "SCREENING_ROOM", "Tiered seating, Dolby Atmos audio and a 180-inch screen", 4, "750", PIC_CINEMA),
        new RoomSpec("Cosmos Rooftop", "ROOFTOP", "Open-air rooftop deck with skyline views and string lighting", 5, "1200", PIC_LOUNGE),
        new RoomSpec("Comet Meeting Suite", "MEETING_ROOM", "Boardroom setup with conference display and whiteboard wall", 6, "500", PIC_CORPORATE)
    );

    private void seedVenueRooms(Long bingeId) {
        Map<String, VenueRoom> existing =
            byName(venueRoomRepository.findByBingeIdOrderBySortOrderAsc(bingeId), VenueRoom::getName);

        List<VenueRoom> dirty = new ArrayList<>();
        int added = 0, photographed = 0;

        for (RoomSpec spec : ROOM_CATALOG) {
            VenueRoom row = existing.get(key(spec.name()));
            if (row == null) {
                dirty.add(VenueRoom.builder()
                    .bingeId(bingeId)
                    .name(spec.name())
                    .roomType(spec.roomType())
                    .capacity(1)
                    .description(spec.description())
                    .sortOrder(spec.sortOrder())
                    .priceAddition(new BigDecimal(spec.priceAddition()))
                    .imageUrls(new ArrayList<>(spec.images()))
                    .build());
                added++;
            } else if (isEmpty(row.getImageUrls())) {
                row.setImageUrls(new ArrayList<>(spec.images()));
                dirty.add(row);
                photographed++;
            }
        }

        if (!dirty.isEmpty()) {
            venueRoomRepository.saveAll(dirty);
            log.info("Binge {}: seeded {} venue rooms, added photos to {}", bingeId, added, photographed);
        }
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
        log.info("Binge {}: seeded {} cancellation tiers", bingeId, tiers.size());
    }

    /* ── Helpers ───────────────────────────────────────────── */

    /** Case/whitespace-insensitive name key, so "Fog effect " never double-seeds "Fog Effect". */
    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static <T> Map<String, T> byName(List<T> rows, Function<T, String> nameOf) {
        Map<String, T> map = new HashMap<>();
        for (T row : rows) map.putIfAbsent(key(nameOf.apply(row)), row);
        return map;
    }

    private static boolean isEmpty(List<String> urls) {
        return urls == null || urls.stream().noneMatch(u -> u != null && !u.isBlank());
    }
}
