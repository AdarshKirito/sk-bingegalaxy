package com.skbingegalaxy.booking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.booking.dto.BingeDto;
import com.skbingegalaxy.booking.dto.BingeSaveRequest;
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
            .createdAt(b.getCreatedAt())
            .build();
    }
}
