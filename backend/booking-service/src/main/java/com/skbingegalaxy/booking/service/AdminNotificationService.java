package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.dto.AdminNotificationDto;
import com.skbingegalaxy.booking.entity.AdminNotification;
import com.skbingegalaxy.booking.repository.AdminNotificationRepository;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * In-app notification inbox for ADMIN / SUPER_ADMIN users.
 *
 * <p>This is intentionally a thin DB-backed inbox rather than a Kafka/email
 * fan-out. Recipients are looked up by id (personal) or by role (broadcast),
 * which keeps booking-service free of cross-service user lookups.
 *
 * <p>Used by the binge approval workflow:
 * <ul>
 *   <li>New pending binge → broadcast to SUPER_ADMIN</li>
 *   <li>Approve / reject → personal notification to the requesting admin</li>
 *   <li>Grace-period warning at 12h, auto-deactivation at 24h → both</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminNotificationService {

    private final AdminNotificationRepository repository;

    /** Personal notification to a single admin/super-admin. */
    @Transactional
    public AdminNotification notifyUser(Long userId,
                                        String role,
                                        String type,
                                        String severity,
                                        String title,
                                        String message,
                                        Long relatedBingeId,
                                        String actionUrl) {
        AdminNotification n = AdminNotification.builder()
            .recipientUserId(userId)
            .recipientRole(role)
            .type(type)
            .severity(severity)
            .title(truncate(title, 200))
            .message(truncate(message, 1000))
            .relatedBingeId(relatedBingeId)
            .actionUrl(actionUrl)
            .build();
        return repository.save(n);
    }

    /**
     * Role-wide broadcast (visible to every user with the given role). Useful
     * when we don't have a specific recipient — e.g. "any super-admin should
     * triage this".
     */
    @Transactional
    public AdminNotification broadcastToRole(String role,
                                             String type,
                                             String severity,
                                             String title,
                                             String message,
                                             Long relatedBingeId,
                                             String actionUrl) {
        AdminNotification n = AdminNotification.builder()
            .recipientUserId(null) // broadcast
            .recipientRole(role)
            .type(type)
            .severity(severity)
            .title(truncate(title, 200))
            .message(truncate(message, 1000))
            .relatedBingeId(relatedBingeId)
            .actionUrl(actionUrl)
            .build();
        return repository.save(n);
    }

    public Page<AdminNotificationDto> list(Long userId, String role, Pageable pageable) {
        return repository.findVisibleToUser(userId, role, pageable).map(this::toDto);
    }

    public long unreadCount(Long userId, String role) {
        return repository.countUnreadForUser(userId, role);
    }

    @Transactional
    public AdminNotificationDto markRead(Long id, Long userId, String role) {
        AdminNotification n = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Notification", "id", id));
        // Authorization: must be the recipient or any user matching a broadcast role.
        boolean isOwnPersonal = n.getRecipientUserId() != null && n.getRecipientUserId().equals(userId);
        boolean isRoleBroadcast = n.getRecipientUserId() == null && n.getRecipientRole().equalsIgnoreCase(role);
        if (!isOwnPersonal && !isRoleBroadcast) {
            throw new ResourceNotFoundException("Notification", "id", id);
        }
        if (n.getReadAt() == null) {
            n.setReadAt(LocalDateTime.now(ZoneOffset.UTC));
            repository.save(n);
        }
        return toDto(n);
    }

    @Transactional
    public int markAllRead(Long userId, String role) {
        return repository.markAllReadForUser(userId, role, LocalDateTime.now(ZoneOffset.UTC));
    }

    private AdminNotificationDto toDto(AdminNotification n) {
        return AdminNotificationDto.builder()
            .id(n.getId())
            .type(n.getType())
            .severity(n.getSeverity())
            .title(n.getTitle())
            .message(n.getMessage())
            .relatedBingeId(n.getRelatedBingeId())
            .actionUrl(n.getActionUrl())
            .readAt(n.getReadAt())
            .createdAt(n.getCreatedAt())
            .build();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
