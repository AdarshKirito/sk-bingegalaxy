package com.skbingegalaxy.availability.service;

import com.skbingegalaxy.availability.client.BookingBingeClient;
import com.skbingegalaxy.availability.dto.BookingBingeDto;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AvailabilityBingeScopeService {

    private final BookingBingeClient bookingBingeClient;

    public Long requireSelectedBinge(String action) {
        Long bingeId = BingeContext.getBingeId();
        if (bingeId == null) {
            throw new BusinessException("Select a binge before " + action, HttpStatus.BAD_REQUEST);
        }
        return bingeId;
    }

    public BookingBingeDto requireManagedBinge(Long adminId, String role, String action) {
        Long bingeId = requireSelectedBinge(action);

        try {
            // userId rides along so the same round trip returns the V71
            // denied-modules list for this admin (module enforcement).
            ApiResponse<BookingBingeDto> response = bookingBingeClient.getBinge(bingeId, adminId);
            BookingBingeDto binge = response != null ? response.getData() : null;
            if (binge == null) {
                throw new ResourceNotFoundException("Binge", "id", bingeId);
            }
            if (!"SUPER_ADMIN".equalsIgnoreCase(role) && !Objects.equals(binge.getAdminId(), adminId)) {
                throw new BusinessException("Access denied: you do not own this binge", HttpStatus.FORBIDDEN);
            }
            return binge;
        } catch (FeignException.NotFound ex) {
            throw new ResourceNotFoundException("Binge", "id", bingeId);
        } catch (FeignException ex) {
            throw new BusinessException("Unable to validate binge ownership right now", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    /**
     * V71 module enforcement: every admin endpoint of this service belongs to
     * the BLOCKED_DATES module. 403 when the super-admin disabled/locked it
     * for this admin in this binge; SUPER_ADMIN is never denied.
     */
    public void requireModuleAllowed(BookingBingeDto binge, String role, String moduleKey) {
        if (binge == null || moduleKey == null || "SUPER_ADMIN".equalsIgnoreCase(role)) return;
        if (binge.getDeniedModules() != null && binge.getDeniedModules().contains(moduleKey)) {
            throw new BusinessException("This option is disabled by Super Admin.", HttpStatus.FORBIDDEN);
        }
    }
}