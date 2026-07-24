package com.skbingegalaxy.payment.service;

import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import com.skbingegalaxy.payment.client.BookingBingeClient;
import com.skbingegalaxy.payment.dto.BookingBingeDto;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PaymentBingeScopeService {

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
     * Validates that the admin owns the binge identified by an EXPLICIT id
     * (e.g. the binge stamped on an approval-request row), independent of the
     * client-controlled {@code X-Binge-Id} header. SUPER_ADMIN always passes.
     *
     * <p>A {@code null} bingeId means the resource is platform-scoped and only
     * SUPER_ADMIN may act on it — binge admins are rejected, never silently
     * allowed (fail closed).</p>
     */
    public void requireBingeOwnership(Long bingeId, Long adminId, String role, String action) {
        if ("SUPER_ADMIN".equalsIgnoreCase(role)) {
            return;
        }
        if (bingeId == null) {
            throw new BusinessException(
                "This record is platform-scoped — only a super admin can " + action,
                HttpStatus.FORBIDDEN);
        }
        try {
            ApiResponse<BookingBingeDto> response = bookingBingeClient.getBinge(bingeId, adminId);
            BookingBingeDto binge = response != null ? response.getData() : null;
            if (binge == null) {
                throw new ResourceNotFoundException("Binge", "id", bingeId);
            }
            if (!Objects.equals(binge.getAdminId(), adminId)) {
                throw new BusinessException("Access denied: you do not own this binge", HttpStatus.FORBIDDEN);
            }
        } catch (FeignException.NotFound ex) {
            throw new ResourceNotFoundException("Binge", "id", bingeId);
        } catch (FeignException ex) {
            // Fail closed: if ownership can't be proven, the action is denied.
            throw new BusinessException("Unable to validate binge ownership right now", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    /**
     * V71 module enforcement: 403 when the super-admin disabled/locked the
     * given module for this admin in this binge. SUPER_ADMIN is never denied.
     */
    public void requireModuleAllowed(BookingBingeDto binge, String role, String moduleKey) {
        if (binge == null || moduleKey == null || "SUPER_ADMIN".equalsIgnoreCase(role)) return;
        if (binge.getDeniedModules() != null && binge.getDeniedModules().contains(moduleKey)) {
            throw new BusinessException("This option is disabled by Super Admin.", HttpStatus.FORBIDDEN);
        }
    }
}