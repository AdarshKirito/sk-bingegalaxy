package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.dto.CancellationTierDto;
import com.skbingegalaxy.booking.dto.CancellationTierSaveRequest;
import com.skbingegalaxy.booking.entity.CancellationTier;
import com.skbingegalaxy.booking.repository.CancellationTierRepository;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class CancellationTierService {

    private final CancellationTierRepository cancellationTierRepository;

    @Transactional(readOnly = true)
    public List<CancellationTierDto> getTiers(Long bingeId) {
        return cancellationTierRepository.findByBingeIdOrderByHoursBeforeStartDesc(bingeId)
            .stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<CancellationTierDto> getActiveBingeTiers() {
        Long bingeId = BingeContext.requireBingeId();
        return getTiers(bingeId);
    }

    /**
     * Replace all cancellation tiers for a binge (bulk save).
     * Validates that hoursBeforeStart values are unique and properly ordered.
     */
    @Transactional
    public List<CancellationTierDto> saveTiers(Long bingeId, CancellationTierSaveRequest request) {
        if (request.getTiers() == null || request.getTiers().isEmpty()) {
            cancellationTierRepository.deleteByBingeId(bingeId);
            log.info("Cleared all cancellation tiers for binge {}", bingeId);
            return List.of();
        }

        // Validate uniqueness of hoursBeforeStart
        long distinctHours = request.getTiers().stream()
            .map(CancellationTierSaveRequest.TierEntry::getHoursBeforeStart)
            .distinct().count();
        if (distinctHours != request.getTiers().size()) {
            throw new BusinessException("Each tier must have a unique 'hours before start' value");
        }

        // Delete old and re-create
        cancellationTierRepository.deleteByBingeId(bingeId);

        List<CancellationTier> tiers = request.getTiers().stream()
            .map(t -> CancellationTier.builder()
                .bingeId(bingeId)
                .hoursBeforeStart(t.getHoursBeforeStart())
                .refundPercentage(t.getRefundPercentage())
                .label(t.getLabel())
                .build())
            .toList();

        List<CancellationTier> saved = cancellationTierRepository.saveAll(tiers);
        log.info("Saved {} cancellation tiers for binge {}", saved.size(), bingeId);
        return saved.stream().map(this::toDto).toList();
    }

    private CancellationTierDto toDto(CancellationTier t) {
        return CancellationTierDto.builder()
            .id(t.getId())
            .bingeId(t.getBingeId())
            .hoursBeforeStart(t.getHoursBeforeStart())
            .refundPercentage(t.getRefundPercentage())
            .label(t.getLabel())
            .build();
    }
}
