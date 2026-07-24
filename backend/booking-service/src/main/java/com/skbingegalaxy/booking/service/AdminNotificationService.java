package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.client.HttpAuthContactClient;
import com.skbingegalaxy.booking.dto.AdminNotificationDto;
import com.skbingegalaxy.booking.entity.AdminNotification;
import com.skbingegalaxy.booking.repository.AdminNotificationRepository;
import com.skbingegalaxy.common.constants.KafkaTopics;
import com.skbingegalaxy.common.enums.NotificationChannel;
import com.skbingegalaxy.common.event.NotificationEvent;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

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
    private final HttpAuthContactClient authContactClient;
    private final BookingEventPublisher eventPublisher;

    /**
     * Fan a newly-created in-app message out to the recipient's out-of-band channels
     * (email today; SMS/WhatsApp/push as the notification-service + preferences allow) so
     * they learn about it without opening the app. Best-effort: resolves the recipient's
     * contact from auth-service and publishes a {@code NEW_MESSAGE} event through the
     * transactional outbox. Only for SPECIFIC recipients — broadcasts (recipientUserId
     * null) stay in-app to avoid an unsolicited mass mail-out. Never throws: a lookup or
     * publish failure must not roll back the message the user just sent.
     */
    private void notifyRecipientOutOfBand(Long recipientUserId, String recipientRole, Long threadId,
                                          String senderName, String title, String body) {
        if (recipientUserId == null) return; // broadcast → in-app only
        try {
            HttpAuthContactClient.AdminContact contact = authContactClient.fetchAdminContact(recipientUserId);
            if (contact == null || contact.getEmail() == null || contact.getEmail().isBlank()) {
                return; // no way to reach them out-of-band
            }
            String from = (senderName == null || senderName.isBlank()) ? "the SK Binge Galaxy team" : senderName;
            String subject = "New message from " + from;
            String preview = (body == null || body.isBlank())
                ? "(sent you an attachment)"
                : (body.length() > 240 ? body.substring(0, 240) + "…" : body);
            NotificationEvent event = NotificationEvent.builder()
                .type("NEW_MESSAGE")
                .channel(NotificationChannel.EMAIL)
                .recipientEmail(contact.getEmail())
                .recipientName((contact.getFirstName() == null ? "" : contact.getFirstName()).trim())
                .recipientPhone(contact.getPhone())
                .recipientPhoneCountryCode(contact.getPhoneCountryCode())
                .subject(subject)
                .body(String.format("You have a new message from %s in SK Binge Galaxy:%n%n\"%s\"%n%nLog in to read and reply.",
                    from, preview))
                // deepLinkUrl drives the browser-push click-through (WebPushService reads it).
                // Role-aware because admins and customers use different message routes.
                .metadata(Map.of(
                    "sender", from,
                    "title", title == null ? "" : title,
                    "recipientUserId", String.valueOf(recipientUserId),
                    "deepLinkUrl", messagesDeepLink(recipientRole, threadId)
                ))
                .build();
            eventPublisher.publish(KafkaTopics.NOTIFICATION_SEND, "MSG-" + recipientUserId, event);
        } catch (Exception e) {
            log.warn("Out-of-band notify skipped for recipient {}: {}", recipientUserId, e.getMessage());
        }
    }

    /**
     * Where a "new message" push should land the recipient. Customers open the customer
     * inbox; staff open the admin messages page deep-linked to the thread (AdminMessages
     * reads {@code ?thread=}). Falls back to the inbox root when the thread id is unknown.
     */
    private static String messagesDeepLink(String recipientRole, Long threadId) {
        if ("CUSTOMER".equalsIgnoreCase(recipientRole)) {
            return "/messages";
        }
        return threadId != null ? "/admin/messages?thread=" + threadId : "/admin/messages";
    }

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
        return repository.findVisibleToUser(userId, role, pageable).map(n -> toDto(n, userId));
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
        return toDto(n, userId);
    }

    @Transactional
    public int markAllRead(Long userId, String role) {
        return repository.markAllReadForUser(userId, role, LocalDateTime.now(ZoneOffset.UTC));
    }

    // ── Messaging (two-way) ──────────────────────────────────────────────────

    /**
     * Compose a new message. The sender is stamped authoritatively from the gateway
     * headers ({@code senderUserId}/{@code senderRole}); display names are labels only.
     * A {@code null recipientUserId} is a role-wide broadcast (e.g. "all super-admins").
     */
    /** Optional media attachment on a message. */
    public record Attachment(String url, String type, String name) {
        boolean isPresent() { return url != null && !url.isBlank(); }
    }

    @Transactional
    public AdminNotificationDto sendMessage(Long senderUserId, String senderRole, String senderName,
                                            Long recipientUserId, String recipientRole, String recipientName,
                                            String title, String message, Attachment attachment) {
        if (recipientRole == null || recipientRole.isBlank()) {
            throw new com.skbingegalaxy.common.exception.BusinessException("A recipient is required");
        }
        boolean hasAttachment = attachment != null && attachment.isPresent();
        if ((message == null || message.isBlank()) && !hasAttachment) {
            throw new com.skbingegalaxy.common.exception.BusinessException("Add a message or an attachment");
        }
        AdminNotification n = AdminNotification.builder()
            .senderUserId(senderUserId)
            .senderRole(senderRole == null ? "ADMIN" : senderRole)
            .senderName(truncate(defaultName(senderName, senderRole, senderUserId), 150))
            .recipientUserId(recipientUserId)
            .recipientRole(recipientRole.toUpperCase())
            .recipientName(truncate(recipientName, 150))
            .type("MESSAGE")
            .severity("INFO")
            .title(truncate(blankToDefault(title, "(no subject)"), 200))
            .message(truncate(message == null ? "" : message, 1000))
            .attachmentUrl(hasAttachment ? truncate(attachment.url(), 500) : null)
            .attachmentType(hasAttachment ? truncate(attachment.type(), 20) : null)
            .attachmentName(hasAttachment ? truncate(attachment.name(), 255) : null)
            .build();
        n = repository.save(n);
        n.setThreadId(n.getId()); // thread starter: thread id == own id
        n = repository.save(n);
        notifyRecipientOutOfBand(recipientUserId, n.getRecipientRole(), n.getThreadId(),
            n.getSenderName(), n.getTitle(), n.getMessage());
        return toDto(n, senderUserId);
    }

    /** A single recipient for {@link #sendBulk}. */
    public record RecipientRef(Long recipientUserId, String recipientRole, String recipientName) {}

    /**
     * Compose the same message to several recipients at once (each gets its own personal
     * thread). Used by the messaging "pick specific admins / super-admins / customers"
     * multi-select. The whole batch is one transaction — either every message is created
     * or none is.
     */
    @Transactional
    public java.util.List<AdminNotificationDto> sendBulk(Long senderUserId, String senderRole, String senderName,
                                                         java.util.List<RecipientRef> recipients,
                                                         String title, String message, Attachment attachment) {
        if (recipients == null || recipients.isEmpty()) {
            throw new com.skbingegalaxy.common.exception.BusinessException("Pick at least one recipient");
        }
        boolean hasAttachment = attachment != null && attachment.isPresent();
        if ((message == null || message.isBlank()) && !hasAttachment) {
            throw new com.skbingegalaxy.common.exception.BusinessException("Add a message or an attachment");
        }
        // De-duplicate identical (role,userId) targets so a double-checked recipient
        // doesn't receive two copies.
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        java.util.List<AdminNotificationDto> out = new java.util.ArrayList<>();
        for (RecipientRef r : recipients) {
            String key = (r.recipientRole() == null ? "" : r.recipientRole().toUpperCase())
                + "#" + (r.recipientUserId() == null ? "*" : r.recipientUserId());
            if (!seen.add(key)) continue;
            out.add(sendMessage(senderUserId, senderRole, senderName,
                r.recipientUserId(), r.recipientRole(), r.recipientName(), title, message, attachment));
        }
        return out;
    }

    /** Reply to a message — routes back to the other party and joins its thread. */
    @Transactional
    public AdminNotificationDto reply(Long parentId, Long senderUserId, String senderRole, String senderName,
                                      String message, Attachment attachment) {
        AdminNotification parent = repository.findById(parentId)
            .orElseThrow(() -> new ResourceNotFoundException("Message", "id", parentId));
        boolean isParticipant = (parent.getSenderUserId() != null && parent.getSenderUserId().equals(senderUserId))
            || (parent.getRecipientUserId() != null && parent.getRecipientUserId().equals(senderUserId))
            || (parent.getRecipientUserId() == null && parent.getRecipientRole() != null
                && parent.getRecipientRole().equalsIgnoreCase(senderRole));
        if (!isParticipant) {
            throw new ResourceNotFoundException("Message", "id", parentId);
        }
        boolean hasAttachment = attachment != null && attachment.isPresent();
        if ((message == null || message.isBlank()) && !hasAttachment) {
            throw new com.skbingegalaxy.common.exception.BusinessException("Add a reply or an attachment");
        }
        // Recipient of the reply = the OTHER party in the parent message.
        Long toUserId; String toRole; String toName;
        if (parent.getSenderUserId() != null && parent.getSenderUserId().equals(senderUserId)) {
            toUserId = parent.getRecipientUserId(); toRole = parent.getRecipientRole(); toName = parent.getRecipientName();
        } else {
            toUserId = parent.getSenderUserId(); toRole = parent.getSenderRole(); toName = parent.getSenderName();
        }
        String subject = parent.getTitle() == null ? "(no subject)"
            : (parent.getTitle().startsWith("Re: ") ? parent.getTitle() : "Re: " + parent.getTitle());
        AdminNotification n = AdminNotification.builder()
            .senderUserId(senderUserId)
            .senderRole(senderRole == null ? "ADMIN" : senderRole)
            .senderName(truncate(defaultName(senderName, senderRole, senderUserId), 150))
            .recipientUserId(toUserId)
            .recipientRole(toRole == null ? "SUPER_ADMIN" : toRole)
            .recipientName(truncate(toName, 150))
            .type("MESSAGE")
            .severity("INFO")
            .title(truncate(subject, 200))
            .message(truncate(message == null ? "" : message, 1000))
            .attachmentUrl(hasAttachment ? truncate(attachment.url(), 500) : null)
            .attachmentType(hasAttachment ? truncate(attachment.type(), 20) : null)
            .attachmentName(hasAttachment ? truncate(attachment.name(), 255) : null)
            .threadId(parent.getThreadId() != null ? parent.getThreadId() : parent.getId())
            .parentId(parent.getId())
            .build();
        n = repository.save(n);
        notifyRecipientOutOfBand(toUserId, n.getRecipientRole(), n.getThreadId(),
            n.getSenderName(), n.getTitle(), n.getMessage());
        return toDto(n, senderUserId);
    }

    /** Full conversation, oldest-first. Caller must be a participant. */
    public java.util.List<AdminNotificationDto> getThread(Long threadId, Long userId, String role) {
        java.util.List<AdminNotification> msgs = repository.findByThreadIdOrderByCreatedAtAsc(threadId);
        boolean participant = msgs.stream().anyMatch(m ->
            (m.getSenderUserId() != null && m.getSenderUserId().equals(userId))
            || (m.getRecipientUserId() != null && m.getRecipientUserId().equals(userId))
            || (m.getRecipientUserId() == null && m.getRecipientRole() != null && m.getRecipientRole().equalsIgnoreCase(role)));
        if (!participant) return java.util.List.of();
        return msgs.stream().map(m -> toDto(m, userId)).toList();
    }

    public Page<AdminNotificationDto> listSent(Long userId, Pageable pageable) {
        return repository.findSentByUser(userId, pageable).map(n -> toDto(n, userId));
    }

    /** Delete a single item the caller owns (recipient or sender). */
    @Transactional
    public void delete(Long id, Long userId, String role) {
        AdminNotification n = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Message", "id", id));
        boolean isRecipient = (n.getRecipientUserId() != null && n.getRecipientUserId().equals(userId))
            || (n.getRecipientUserId() == null && n.getRecipientRole() != null && n.getRecipientRole().equalsIgnoreCase(role));
        boolean isSender = n.getSenderUserId() != null && n.getSenderUserId().equals(userId);
        if (!isRecipient && !isSender) {
            throw new ResourceNotFoundException("Message", "id", id);
        }
        repository.deleteById(id);
    }

    @Transactional
    public int clearRead(Long userId, String role) {
        return repository.clearReadForUser(userId, role);
    }

    private AdminNotificationDto toDto(AdminNotification n, Long callerUserId) {
        boolean system = n.getSenderRole() == null || "SYSTEM".equalsIgnoreCase(n.getSenderRole());
        boolean mine = n.getSenderUserId() != null && n.getSenderUserId().equals(callerUserId);
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
            .senderUserId(n.getSenderUserId())
            .senderRole(n.getSenderRole())
            .senderName(n.getSenderName())
            .recipientUserId(n.getRecipientUserId())
            .recipientRole(n.getRecipientRole())
            .recipientName(n.getRecipientName())
            .threadId(n.getThreadId())
            .parentId(n.getParentId())
            .mine(mine)
            .system(system)
            .attachmentUrl(n.getAttachmentUrl())
            .attachmentType(n.getAttachmentType())
            .attachmentName(n.getAttachmentName())
            .build();
    }

    private static String defaultName(String name, String role, Long userId) {
        if (name != null && !name.isBlank()) return name;
        String r = role == null ? "User" : switch (role.toUpperCase()) {
            case "SUPER_ADMIN" -> "Super Admin";
            case "ADMIN" -> "Admin";
            case "CUSTOMER" -> "Customer";
            default -> "User";
        };
        return userId != null ? r + " #" + userId : r;
    }

    private static String blankToDefault(String s, String dflt) {
        return (s == null || s.isBlank()) ? dflt : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
