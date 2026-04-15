package com.skbingegalaxy.booking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.booking.dto.BingeDto;
import com.skbingegalaxy.booking.dto.BingeSaveRequest;
import com.skbingegalaxy.booking.dto.CustomerAboutExperienceDto;
import com.skbingegalaxy.booking.dto.CustomerAboutHighlightDto;
import com.skbingegalaxy.booking.dto.CustomerAboutPolicyDto;
import com.skbingegalaxy.booking.dto.CustomerDashboardExperienceDto;
import com.skbingegalaxy.booking.dto.CustomerDashboardSlideDto;
import com.skbingegalaxy.booking.entity.Binge;
import com.skbingegalaxy.booking.repository.AddOnRepository;
import com.skbingegalaxy.booking.repository.BingeRepository;
import com.skbingegalaxy.booking.repository.BookingRepository;
import com.skbingegalaxy.booking.repository.CustomerPricingProfileRepository;
import com.skbingegalaxy.booking.repository.EventTypeRepository;
import com.skbingegalaxy.booking.repository.RateCodeRepository;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.DuplicateResourceException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class BingeService {

    private static final String DEFAULT_DASHBOARD_EYEBROW = "Explore Experiences";
    private static final String DEFAULT_DASHBOARD_TITLE = "Pick a setup that matches the mood";
    private static final String DEFAULT_DASHBOARD_LAYOUT = "GRID";
    private static final String DEFAULT_SLIDE_BADGE = "Featured";
    private static final String DEFAULT_SLIDE_HEADLINE = "Custom setup";
    private static final String DEFAULT_SLIDE_DESCRIPTION = "Guide customers toward the atmosphere, offers, or experiences you want this venue to highlight first.";
    private static final String DEFAULT_SLIDE_CTA = "Open Booking";
    private static final String DEFAULT_SLIDE_THEME = "celebration";
    private static final String DEFAULT_ABOUT_EYEBROW = "Before You Book";
    private static final String DEFAULT_ABOUT_TITLE = "Know your binge before event day";
    private static final String DEFAULT_ABOUT_HERO_TITLE = "Everything customers should know, in one place";
    private static final String DEFAULT_ABOUT_HERO_DESCRIPTION = "Set expectations clearly with venue highlights, house rules, and policies so guests walk in prepared and confident.";
    private static final String DEFAULT_ABOUT_HIGHLIGHTS_TITLE = "Why guests choose this binge";
    private static final String DEFAULT_ABOUT_HOUSE_RULES_TITLE = "House rules";
    private static final String DEFAULT_ABOUT_POLICY_TITLE = "Policies and regulations";
    private static final String DEFAULT_ABOUT_CONTACT_HEADING = "Need help before your slot?";
    private static final String DEFAULT_ABOUT_CONTACT_DESCRIPTION = "Use the support contacts listed for this binge and include your booking reference for quicker help.";

    private final BingeRepository bingeRepository;
    private final BookingRepository bookingRepository;
    private final EventTypeRepository eventTypeRepository;
    private final AddOnRepository addOnRepository;
    private final RateCodeRepository rateCodeRepository;
    private final CustomerPricingProfileRepository customerPricingProfileRepository;
    private final ObjectMapper objectMapper;

    public List<BingeDto> getAdminBinges(Long adminId, String role) {
        if ("SUPER_ADMIN".equals(role)) {
            return bingeRepository.findAllByOrderByCreatedAtDesc()
                .stream().map(this::toDto).toList();
        }
        return bingeRepository.findByAdminIdOrderByCreatedAtDesc(adminId)
            .stream().map(this::toDto).toList();
    }

    public List<BingeDto> getAllActiveBinges() {
        return bingeRepository.findByActiveTrueOrderByNameAsc()
            .stream().map(this::toDto).toList();
    }

    public List<BingeDto> getBingesByAdminId(Long adminId) {
        return bingeRepository.findByAdminIdOrderByCreatedAtDesc(adminId)
            .stream().map(this::toDto).toList();
    }

    public BingeDto getBingeById(Long id) {
        return toDto(bingeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Binge", "id", id)));
    }

    public CustomerDashboardExperienceDto getCustomerDashboardExperience(Long id) {
        Binge binge = bingeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Binge", "id", id));
        return readDashboardExperience(binge.getCustomerDashboardConfigJson());
    }

    public CustomerDashboardExperienceDto getAdminCustomerDashboardExperience(Long id, Long adminId, String role) {
        return readDashboardExperience(getManagedBinge(id, adminId, role).getCustomerDashboardConfigJson());
    }

    public CustomerAboutExperienceDto getCustomerAboutExperience(Long id) {
        Binge binge = bingeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Binge", "id", id));
        return readAboutExperience(binge.getCustomerAboutConfigJson());
    }

    public CustomerAboutExperienceDto getAdminCustomerAboutExperience(Long id, Long adminId, String role) {
        return readAboutExperience(getManagedBinge(id, adminId, role).getCustomerAboutConfigJson());
    }

    @Transactional
    public BingeDto createBinge(BingeSaveRequest request, Long adminId, LocalDate clientDate) {
        if (bingeRepository.existsByNameAndAdminId(request.getName(), adminId)) {
            throw new DuplicateResourceException("Binge", "name", request.getName());
        }

        LocalDate opDate = clientDate != null ? clientDate : LocalDate.now();
        Binge binge = Binge.builder()
            .name(request.getName())
            .address(request.getAddress())
            .adminId(adminId)
            .active(true)
            .operationalDate(opDate)
            .supportEmail(trimToNull(request.getSupportEmail()))
            .supportPhone(trimToNull(request.getSupportPhone()))
            .supportWhatsapp(trimToNull(request.getSupportWhatsapp()))
            .customerCancellationEnabled(request.getCustomerCancellationEnabled() == null || request.getCustomerCancellationEnabled())
            .customerCancellationCutoffMinutes(request.getCustomerCancellationCutoffMinutes() == null ? 180 : request.getCustomerCancellationCutoffMinutes())
            .maxConcurrentBookings(request.getMaxConcurrentBookings())
            .build();

        binge = bingeRepository.save(binge);
        log.info("Binge created: '{}' by admin {}", binge.getName(), adminId);
        return toDto(binge);
    }

    @Transactional
    public BingeDto updateBinge(Long id, BingeSaveRequest request, Long adminId, String role) {
        Binge binge = getManagedBinge(id, adminId, role);

        binge.setName(request.getName());
        binge.setAddress(request.getAddress());
        binge.setSupportEmail(trimToNull(request.getSupportEmail()));
        binge.setSupportPhone(trimToNull(request.getSupportPhone()));
        binge.setSupportWhatsapp(trimToNull(request.getSupportWhatsapp()));
        binge.setCustomerCancellationEnabled(request.getCustomerCancellationEnabled() == null || request.getCustomerCancellationEnabled());
        binge.setCustomerCancellationCutoffMinutes(request.getCustomerCancellationCutoffMinutes() == null ? binge.getCustomerCancellationCutoffMinutes() : request.getCustomerCancellationCutoffMinutes());
        binge.setMaxConcurrentBookings(request.getMaxConcurrentBookings());
        binge = bingeRepository.save(binge);
        log.info("Binge updated: '{}' (ID: {})", binge.getName(), id);
        return toDto(binge);
    }

    @Transactional
    public CustomerDashboardExperienceDto updateCustomerDashboardExperience(Long id,
                                                                            CustomerDashboardExperienceDto request,
                                                                            Long adminId,
                                                                            String role) {
        Binge binge = getManagedBinge(id, adminId, role);
        CustomerDashboardExperienceDto normalized = normalizeDashboardExperience(request);
        binge.setCustomerDashboardConfigJson(writeDashboardExperience(normalized));
        bingeRepository.save(binge);
        log.info("Customer dashboard experience updated for binge {}", id);
        return normalized;
    }

    @Transactional
    public CustomerAboutExperienceDto updateCustomerAboutExperience(Long id,
                                                                    CustomerAboutExperienceDto request,
                                                                    Long adminId,
                                                                    String role) {
        Binge binge = getManagedBinge(id, adminId, role);
        CustomerAboutExperienceDto normalized = normalizeAboutExperience(request);
        binge.setCustomerAboutConfigJson(writeAboutExperience(normalized));
        bingeRepository.save(binge);
        log.info("Customer about experience updated for binge {}", id);
        return normalized;
    }

    @Transactional
    public void toggleBinge(Long id, Long adminId, String role) {
        Binge binge = getManagedBinge(id, adminId, role);

        binge.setActive(!binge.isActive());
        bingeRepository.save(binge);
        log.info("Binge toggled: '{}' active={}", binge.getName(), binge.isActive());
    }

    @Transactional
    public void deleteBinge(Long id, Long adminId, String role) {
        Binge binge = getManagedBinge(id, adminId, role);
        if (binge.isActive()) {
            throw new BusinessException("Deactivate the binge before deleting it");
        }
        if (bookingRepository.existsByBingeId(id)) {
            throw new BusinessException("Cannot delete this binge because it already has bookings");
        }
        if (eventTypeRepository.existsByBingeId(id)) {
            throw new BusinessException("Delete this binge's event types before deleting the binge");
        }
        if (addOnRepository.existsByBingeId(id)) {
            throw new BusinessException("Delete this binge's add-ons before deleting the binge");
        }
        if (rateCodeRepository.existsByBingeId(id)) {
            throw new BusinessException("Delete this binge's rate codes before deleting the binge");
        }
        if (customerPricingProfileRepository.existsByBingeId(id)) {
            throw new BusinessException("Delete this binge's customer pricing profiles before deleting the binge");
        }

        bingeRepository.delete(binge);
        log.info("Binge deleted: '{}' (ID: {})", binge.getName(), id);
    }

    private Binge getManagedBinge(Long id, Long adminId, String role) {
        Binge binge = bingeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Binge", "id", id));

        if (!"SUPER_ADMIN".equals(role) && !binge.getAdminId().equals(adminId)) {
            throw new BusinessException("Access denied: you do not own this binge", HttpStatus.FORBIDDEN);
        }
        return binge;
    }

    private CustomerDashboardExperienceDto readDashboardExperience(String rawConfigJson) {
        if (rawConfigJson == null || rawConfigJson.isBlank()) {
            return normalizeDashboardExperience(null);
        }
        try {
            CustomerDashboardExperienceDto parsed = objectMapper.readValue(rawConfigJson, CustomerDashboardExperienceDto.class);
            return normalizeDashboardExperience(parsed);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to parse customer dashboard config JSON. Falling back to defaults.", ex);
            return normalizeDashboardExperience(null);
        }
    }

    private String writeDashboardExperience(CustomerDashboardExperienceDto config) {
        try {
            return objectMapper.writeValueAsString(normalizeDashboardExperience(config));
        } catch (JsonProcessingException ex) {
            throw new BusinessException("Failed to store customer dashboard experience", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private CustomerAboutExperienceDto readAboutExperience(String rawConfigJson) {
        if (rawConfigJson == null || rawConfigJson.isBlank()) {
            return normalizeAboutExperience(null);
        }
        try {
            CustomerAboutExperienceDto parsed = objectMapper.readValue(rawConfigJson, CustomerAboutExperienceDto.class);
            return normalizeAboutExperience(parsed);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to parse customer about config JSON. Falling back to defaults.", ex);
            return normalizeAboutExperience(null);
        }
    }

    private String writeAboutExperience(CustomerAboutExperienceDto config) {
        try {
            return objectMapper.writeValueAsString(normalizeAboutExperience(config));
        } catch (JsonProcessingException ex) {
            throw new BusinessException("Failed to store customer about experience", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private CustomerDashboardExperienceDto normalizeDashboardExperience(CustomerDashboardExperienceDto raw) {
        List<CustomerDashboardSlideDto> normalizedSlides = new ArrayList<>();
        if (raw != null && raw.getSlides() != null) {
            for (CustomerDashboardSlideDto slide : raw.getSlides()) {
                CustomerDashboardSlideDto normalizedSlide = normalizeSlide(slide);
                if (normalizedSlide != null) {
                    normalizedSlides.add(normalizedSlide);
                }
                if (normalizedSlides.size() == 6) {
                    break;
                }
            }
        }

        return CustomerDashboardExperienceDto.builder()
            .sectionEyebrow(defaultIfBlank(raw != null ? raw.getSectionEyebrow() : null, DEFAULT_DASHBOARD_EYEBROW))
            .sectionTitle(defaultIfBlank(raw != null ? raw.getSectionTitle() : null, DEFAULT_DASHBOARD_TITLE))
            .sectionSubtitle(trimToNull(raw != null ? raw.getSectionSubtitle() : null))
            .layout(normalizeLayout(raw != null ? raw.getLayout() : null))
            .slides(normalizedSlides)
            .build();
    }

    private CustomerDashboardSlideDto normalizeSlide(CustomerDashboardSlideDto raw) {
        if (raw == null) {
            return null;
        }

        String badge = trimToNull(raw.getBadge());
        String headline = trimToNull(raw.getHeadline());
        String description = trimToNull(raw.getDescription());
        String ctaLabel = trimToNull(raw.getCtaLabel());
        String imageUrl = trimToNull(raw.getImageUrl());

        if (badge == null && headline == null && description == null && ctaLabel == null && imageUrl == null) {
            return null;
        }

        return CustomerDashboardSlideDto.builder()
            .badge(defaultIfBlank(badge, DEFAULT_SLIDE_BADGE))
            .headline(defaultIfBlank(headline, DEFAULT_SLIDE_HEADLINE))
            .description(defaultIfBlank(description, DEFAULT_SLIDE_DESCRIPTION))
            .ctaLabel(defaultIfBlank(ctaLabel, DEFAULT_SLIDE_CTA))
            .imageUrl(imageUrl)
            .theme(normalizeTheme(raw.getTheme()))
            .build();
    }

    private CustomerAboutExperienceDto normalizeAboutExperience(CustomerAboutExperienceDto raw) {
        List<CustomerAboutHighlightDto> normalizedHighlights = normalizeHighlights(raw != null ? raw.getHighlights() : null);
        List<String> normalizedHouseRules = normalizeHouseRules(raw != null ? raw.getHouseRules() : null);
        List<CustomerAboutPolicyDto> normalizedPolicies = normalizePolicies(raw != null ? raw.getPolicies() : null);

        return CustomerAboutExperienceDto.builder()
            .sectionEyebrow(defaultIfBlank(raw != null ? raw.getSectionEyebrow() : null, DEFAULT_ABOUT_EYEBROW))
            .sectionTitle(defaultIfBlank(raw != null ? raw.getSectionTitle() : null, DEFAULT_ABOUT_TITLE))
            .sectionSubtitle(trimToNull(raw != null ? raw.getSectionSubtitle() : null))
            .heroTitle(defaultIfBlank(raw != null ? raw.getHeroTitle() : null, DEFAULT_ABOUT_HERO_TITLE))
            .heroDescription(defaultIfBlank(raw != null ? raw.getHeroDescription() : null, DEFAULT_ABOUT_HERO_DESCRIPTION))
            .highlightsTitle(defaultIfBlank(raw != null ? raw.getHighlightsTitle() : null, DEFAULT_ABOUT_HIGHLIGHTS_TITLE))
            .highlights(normalizedHighlights)
            .houseRulesTitle(defaultIfBlank(raw != null ? raw.getHouseRulesTitle() : null, DEFAULT_ABOUT_HOUSE_RULES_TITLE))
            .houseRules(normalizedHouseRules)
            .policyTitle(defaultIfBlank(raw != null ? raw.getPolicyTitle() : null, DEFAULT_ABOUT_POLICY_TITLE))
            .policies(normalizedPolicies)
            .contactHeading(defaultIfBlank(raw != null ? raw.getContactHeading() : null, DEFAULT_ABOUT_CONTACT_HEADING))
            .contactDescription(defaultIfBlank(raw != null ? raw.getContactDescription() : null, DEFAULT_ABOUT_CONTACT_DESCRIPTION))
            .build();
    }

    private List<CustomerAboutHighlightDto> normalizeHighlights(List<CustomerAboutHighlightDto> rawHighlights) {
        List<CustomerAboutHighlightDto> normalized = new ArrayList<>();
        if (rawHighlights != null) {
            for (CustomerAboutHighlightDto highlight : rawHighlights) {
                if (highlight == null) {
                    continue;
                }
                String title = trimToNull(highlight.getTitle());
                String description = trimToNull(highlight.getDescription());
                if (title == null && description == null) {
                    continue;
                }
                normalized.add(CustomerAboutHighlightDto.builder()
                    .title(defaultIfBlank(title, "Guest-first service"))
                    .description(defaultIfBlank(description, "Use this section to highlight what makes your binge special."))
                    .build());
                if (normalized.size() == 8) {
                    break;
                }
            }
        }

        if (normalized.isEmpty()) {
            normalized.add(CustomerAboutHighlightDto.builder()
                .title("Private cinematic setup")
                .description("Your booking includes a private room flow designed for your event's mood and timing.")
                .build());
            normalized.add(CustomerAboutHighlightDto.builder()
                .title("Flexible celebration planning")
                .description("Add-ons, guest counts, and event details can be tuned around your exact occasion.")
                .build());
            normalized.add(CustomerAboutHighlightDto.builder()
                .title("Clear support channel")
                .description("Reach the venue team quickly with your booking reference before and on event day.")
                .build());
        }
        return normalized;
    }

    private List<String> normalizeHouseRules(List<String> rawHouseRules) {
        List<String> normalized = new ArrayList<>();
        if (rawHouseRules != null) {
            for (String rule : rawHouseRules) {
                String item = trimToNull(rule);
                if (item == null) {
                    continue;
                }
                normalized.add(item);
                if (normalized.size() == 12) {
                    break;
                }
            }
        }

        if (normalized.isEmpty()) {
            normalized.add("Arrive at least 15 minutes before your slot to complete check-in smoothly.");
            normalized.add("Carry your booking reference for support and on-site verification.");
            normalized.add("Outside food, decor, or equipment must follow the venue's prior approval policy.");
        }

        return normalized;
    }

    private List<CustomerAboutPolicyDto> normalizePolicies(List<CustomerAboutPolicyDto> rawPolicies) {
        List<CustomerAboutPolicyDto> normalized = new ArrayList<>();
        if (rawPolicies != null) {
            for (CustomerAboutPolicyDto policy : rawPolicies) {
                if (policy == null) {
                    continue;
                }
                String title = trimToNull(policy.getTitle());
                String description = trimToNull(policy.getDescription());
                if (title == null && description == null) {
                    continue;
                }
                normalized.add(CustomerAboutPolicyDto.builder()
                    .title(defaultIfBlank(title, "Policy"))
                    .description(defaultIfBlank(description, "Describe this policy clearly so customers know what to expect."))
                    .build());
                if (normalized.size() == 8) {
                    break;
                }
            }
        }

        if (normalized.isEmpty()) {
            normalized.add(CustomerAboutPolicyDto.builder()
                .title("Payment policy")
                .description("Bookings stay reserved based on the payment status shown in your booking and payments portal.")
                .build());
            normalized.add(CustomerAboutPolicyDto.builder()
                .title("Rescheduling and cancellation")
                .description("Cancellation and rescheduling options depend on your binge's configured timing rules.")
                .build());
        }

        return normalized;
    }

    private String normalizeLayout(String layout) {
        return "CAROUSEL".equalsIgnoreCase(trimToNull(layout)) ? "CAROUSEL" : DEFAULT_DASHBOARD_LAYOUT;
    }

    private String normalizeTheme(String theme) {
        String normalized = trimToNull(theme);
        if (normalized == null) {
            return DEFAULT_SLIDE_THEME;
        }
        return switch (normalized.toLowerCase(Locale.ROOT)) {
            case "celebration", "romance", "cinema", "team", "family", "luxury" -> normalized.toLowerCase(Locale.ROOT);
            default -> DEFAULT_SLIDE_THEME;
        };
    }

    private String defaultIfBlank(String value, String fallback) {
        String normalized = trimToNull(value);
        return normalized != null ? normalized : fallback;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private BingeDto toDto(Binge b) {
        return BingeDto.builder()
            .id(b.getId())
            .name(b.getName())
            .address(b.getAddress())
            .adminId(b.getAdminId())
            .active(b.isActive())
            .operationalDate(b.getOperationalDate())
            .supportEmail(b.getSupportEmail())
            .supportPhone(b.getSupportPhone())
            .supportWhatsapp(b.getSupportWhatsapp())
            .customerCancellationEnabled(b.isCustomerCancellationEnabled())
            .customerCancellationCutoffMinutes(b.getCustomerCancellationCutoffMinutes())
            .maxConcurrentBookings(b.getMaxConcurrentBookings())
            .createdAt(b.getCreatedAt())
            .build();
    }
}
