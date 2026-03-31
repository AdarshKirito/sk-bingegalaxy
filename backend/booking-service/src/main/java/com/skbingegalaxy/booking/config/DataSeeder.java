package com.skbingegalaxy.booking.config;

import com.skbingegalaxy.booking.entity.AddOn;
import com.skbingegalaxy.booking.entity.EventType;
import com.skbingegalaxy.booking.repository.AddOnRepository;
import com.skbingegalaxy.booking.repository.EventTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements CommandLineRunner {

    private final EventTypeRepository eventTypeRepository;
    private final AddOnRepository addOnRepository;

    @Override
    public void run(String... args) {
        seedEventTypes();
        seedAddOns();
    }

    private void seedEventTypes() {
        if (eventTypeRepository.count() > 0) return;

        List<EventType> types = List.of(
            EventType.builder().name("Birthday Celebration").description("Private theater birthday party with decorations").basePrice(new BigDecimal("2999")).hourlyRate(new BigDecimal("500")).minHours(2).maxHours(6).build(),
            EventType.builder().name("Anniversary Special").description("Romantic anniversary celebration setup").basePrice(new BigDecimal("3499")).hourlyRate(new BigDecimal("600")).minHours(2).maxHours(5).build(),
            EventType.builder().name("Surprise Proposal").description("Elegant proposal setup with premium decorations").basePrice(new BigDecimal("4999")).hourlyRate(new BigDecimal("700")).minHours(2).maxHours(4).build(),
            EventType.builder().name("HD Screening").description("Private HD movie screening experience").basePrice(new BigDecimal("1999")).hourlyRate(new BigDecimal("400")).minHours(2).maxHours(6).build(),
            EventType.builder().name("Corporate Event").description("Professional corporate meeting or presentation").basePrice(new BigDecimal("3999")).hourlyRate(new BigDecimal("800")).minHours(2).maxHours(8).build(),
            EventType.builder().name("Baby Shower").description("Themed baby shower celebration").basePrice(new BigDecimal("3499")).hourlyRate(new BigDecimal("500")).minHours(2).maxHours(5).build(),
            EventType.builder().name("Custom Event").description("Create your own custom event experience").basePrice(new BigDecimal("2499")).hourlyRate(new BigDecimal("500")).minHours(1).maxHours(8).build()
        );
        eventTypeRepository.saveAll(types);
        log.info("Seeded {} event types", types.size());
    }

    private void seedAddOns() {
        if (addOnRepository.count() > 0) return;

        List<AddOn> addOns = List.of(
            // DECORATION
            AddOn.builder().name("Basic Decoration").description("Balloons and ribbons").price(new BigDecimal("499")).category("DECORATION").build(),
            AddOn.builder().name("Premium Decoration").description("Premium themed decoration with LED lights").price(new BigDecimal("1499")).category("DECORATION").build(),
            AddOn.builder().name("Flower Decoration").description("Fresh flower arrangements").price(new BigDecimal("999")).category("DECORATION").build(),

            // BEVERAGE
            AddOn.builder().name("Soft Drinks Pack").description("6 assorted cold drinks").price(new BigDecimal("299")).category("BEVERAGE").build(),
            AddOn.builder().name("Premium Beverage Pack").description("Mocktails and fresh juices").price(new BigDecimal("599")).category("BEVERAGE").build(),

            // PHOTOGRAPHY
            AddOn.builder().name("Photo Shoot (30 min)").description("Professional photography session").price(new BigDecimal("1999")).category("PHOTOGRAPHY").build(),
            AddOn.builder().name("Photo + Video Shoot").description("Photos and cinematic video coverage").price(new BigDecimal("3999")).category("PHOTOGRAPHY").build(),

            // EFFECT
            AddOn.builder().name("Fog Effect").description("Dramatic fog machine effects").price(new BigDecimal("799")).category("EFFECT").build(),
            AddOn.builder().name("Red Carpet Entry").description("VIP red carpet welcome").price(new BigDecimal("999")).category("EFFECT").build(),
            AddOn.builder().name("Confetti Blast").description("Confetti cannon celebration").price(new BigDecimal("499")).category("EFFECT").build(),

            // FOOD
            AddOn.builder().name("Birthday Cake (1 kg)").description("Custom designer cake").price(new BigDecimal("799")).category("FOOD").build(),
            AddOn.builder().name("Premium Cake (2 kg)").description("Premium multi-tier designer cake").price(new BigDecimal("1499")).category("FOOD").build(),
            AddOn.builder().name("Snacks Platter").description("Assorted finger food and snacks").price(new BigDecimal("699")).category("FOOD").build(),

            // EXPERIENCE
            AddOn.builder().name("Live Music (1 hour)").description("Acoustic live performance").price(new BigDecimal("2999")).category("EXPERIENCE").build()
        );
        addOnRepository.saveAll(addOns);
        log.info("Seeded {} add-ons", addOns.size());
    }
}
