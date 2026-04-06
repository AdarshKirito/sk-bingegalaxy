package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.dto.BingeDto;
import com.skbingegalaxy.booking.dto.BingeSaveRequest;
import com.skbingegalaxy.booking.entity.Binge;
import com.skbingegalaxy.booking.repository.BingeRepository;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.DuplicateResourceException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class BingeService {

    private final BingeRepository bingeRepository;

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
        Binge binge = bingeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Binge", "id", id));

        if (!"SUPER_ADMIN".equals(role) && !binge.getAdminId().equals(adminId)) {
            throw new BusinessException("Access denied: you do not own this binge", HttpStatus.FORBIDDEN);
        }

        binge.setName(request.getName());
        binge.setAddress(request.getAddress());
        binge = bingeRepository.save(binge);
        log.info("Binge updated: '{}' (ID: {})", binge.getName(), id);
        return toDto(binge);
    }

    @Transactional
    public void toggleBinge(Long id, Long adminId, String role) {
        Binge binge = bingeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Binge", "id", id));

        if (!"SUPER_ADMIN".equals(role) && !binge.getAdminId().equals(adminId)) {
            throw new BusinessException("Access denied: you do not own this binge", HttpStatus.FORBIDDEN);
        }

        binge.setActive(!binge.isActive());
        bingeRepository.save(binge);
        log.info("Binge toggled: '{}' active={}", binge.getName(), binge.isActive());
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
