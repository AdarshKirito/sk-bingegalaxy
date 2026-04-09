package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.entity.Binge;
import com.skbingegalaxy.booking.repository.BingeRepository;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminBingeScopeService {

    private final BingeRepository bingeRepository;

    public Binge requireManagedBinge(Long adminId, String role) {
        return requireManagedBinge(adminId, role, "managing pricing");
    }

    public Long requireSelectedBinge(String action) {
        Long bingeId = BingeContext.getBingeId();
        if (bingeId == null) {
            throw new BusinessException("Select a binge before " + action, HttpStatus.BAD_REQUEST);
        }

        return bingeId;
    }

    public Binge requireManagedBinge(Long adminId, String role, String action) {
        Long bingeId = requireSelectedBinge(action);

        Binge binge = bingeRepository.findById(bingeId)
            .orElseThrow(() -> new ResourceNotFoundException("Binge", "id", bingeId));

        if (!"SUPER_ADMIN".equalsIgnoreCase(role) && !binge.getAdminId().equals(adminId)) {
            throw new BusinessException("Access denied: you do not own this binge", HttpStatus.FORBIDDEN);
        }

        return binge;
    }
}