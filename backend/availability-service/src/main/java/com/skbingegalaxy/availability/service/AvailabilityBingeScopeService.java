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
            ApiResponse<BookingBingeDto> response = bookingBingeClient.getBinge(bingeId);
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
}