package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.dto.AdminNotificationDto;
import com.skbingegalaxy.booking.service.AdminNotificationService;
import com.skbingegalaxy.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Self-scoped message inbox for the CURRENT authenticated user, regardless of role.
 *
 * <p>This is the customer-facing half of the messaging system: it lets a customer
 * read messages an admin/super-admin sent them, reply within a thread, and open a
 * new conversation with platform <em>Support</em>. Every method is filtered by the
 * caller's own {@code X-User-Id}, so a user only ever sees their own mail — the path
 * is deliberately NOT under {@code /admin/**} so it is reachable by CUSTOMER tokens
 * (which {@link com.skbingegalaxy.booking.config.SecurityConfig} blocks from admin
 * paths). The underlying {@link AdminNotificationService} enforces participant/owner
 * checks on every thread, reply, and delete.
 *
 * <p>Security note: a customer may only <em>compose</em> to Support (SUPER_ADMIN,
 * broadcast) — the recipient is fixed server-side here so a customer can never target
 * an arbitrary user id. Replies are constrained to threads they already participate in.
 */
@RestController
@RequestMapping("/api/v1/bookings/notifications")
@RequiredArgsConstructor
public class MyNotificationController {

    private final AdminNotificationService service;
    private final com.skbingegalaxy.booking.repository.BingeRepository bingeRepository;

    /** Paginated messages visible to the caller (personal + role broadcasts targeting them). */
    @GetMapping
    public ResponseEntity<ApiResponse<Page<AdminNotificationDto>>> list(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
            service.list(userId, role, PageRequest.of(page, Math.min(size, 50)))));
    }

    /** Unread badge counter for the caller. */
    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<Map<String, Long>>> unreadCount(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.ok(Map.of("unread", service.unreadCount(userId, role))));
    }

    /** Messages the caller has SENT (their "Sent" folder). */
    @GetMapping("/sent")
    public ResponseEntity<ApiResponse<Page<AdminNotificationDto>>> sent(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(service.listSent(userId, PageRequest.of(page, Math.min(size, 50)))));
    }

    /** Full conversation thread (oldest-first). Service enforces participant check. */
    @GetMapping("/thread/{threadId}")
    public ResponseEntity<ApiResponse<java.util.List<AdminNotificationDto>>> thread(
            @PathVariable Long threadId,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.ok(service.getThread(threadId, userId, role)));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<ApiResponse<AdminNotificationDto>> markRead(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.ok(service.markRead(id, userId, role)));
    }

    @PostMapping("/read-all")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> markAllRead(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.ok(Map.of("updated", service.markAllRead(userId, role))));
    }

    /** Reply within a conversation the caller participates in — routes back to the other party. */
    @PostMapping("/{id}/reply")
    public ResponseEntity<ApiResponse<AdminNotificationDto>> reply(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role,
            @RequestBody ReplyRequest body) {
        return ResponseEntity.ok(ApiResponse.ok("Reply sent",
            service.reply(id, userId, role, body.getSenderName(), body.getMessage(),
                new AdminNotificationService.Attachment(body.getAttachmentUrl(), body.getAttachmentType(), body.getAttachmentName()))));
    }

    /**
     * Open a new conversation with support. Routing follows where the customer IS:
     * <ul>
     *   <li>{@code bingeId} present (customer is inside a venue's dashboard) —
     *       the message goes to THAT binge's admin, who runs the venue and can
     *       actually resolve venue-level issues.</li>
     *   <li>No {@code bingeId} (platform-level pages) — the message goes to the
     *       SUPER_ADMIN role (platform support broadcast).</li>
     * </ul>
     * The recipient is resolved server-side from the binge record — a customer can
     * only pick WHICH venue, never an arbitrary user, so this endpoint is not an
     * arbitrary-messaging vector.
     */
    @PostMapping("/contact-support")
    public ResponseEntity<ApiResponse<AdminNotificationDto>> contactSupport(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role,
            @RequestBody SupportMessageRequest body) {
        Long recipientId = null;
        String recipientRole = "SUPER_ADMIN";
        String recipientName = "Support";
        if (body.getBingeId() != null) {
            var binge = bingeRepository.findById(body.getBingeId()).orElse(null);
            // Only APPROVED venues receive direct customer mail — a pending or
            // rejected binge's admin is not a customer-facing operator yet.
            if (binge != null && binge.getAdminId() != null
                    && binge.getStatus() == com.skbingegalaxy.booking.entity.BingeApprovalStatus.APPROVED) {
                recipientId = binge.getAdminId();
                recipientRole = "ADMIN";
                recipientName = binge.getName() != null ? binge.getName() : "Venue";
            }
            // Unknown / unapproved / ownerless venue → fall through to platform
            // support rather than dropping the customer's message.
        }
        AdminNotificationDto dto = service.sendMessage(
            userId, role, body.getSenderName(),
            recipientId, recipientRole, recipientName,
            body.getTitle(), body.getMessage(),
            new AdminNotificationService.Attachment(body.getAttachmentUrl(), body.getAttachmentType(), body.getAttachmentName()));
        return ResponseEntity.ok(ApiResponse.ok(
            recipientId != null ? "Message sent to " + recipientName : "Message sent to Support", dto));
    }

    /** Delete a single message the caller owns (recipient or sender). */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role) {
        service.delete(id, userId, role);
        return ResponseEntity.ok(ApiResponse.ok("Deleted", null));
    }

    @lombok.Data
    public static class ReplyRequest {
        private String senderName;
        private String message;
        private String attachmentUrl;
        private String attachmentType;
        private String attachmentName;
    }

    @lombok.Data
    public static class SupportMessageRequest {
        private String senderName;
        private String title;
        private String message;
        private String attachmentUrl;
        private String attachmentType;
        private String attachmentName;
        /** Venue the customer is messaging FROM (null = platform-level support). */
        private Long bingeId;
    }
}
