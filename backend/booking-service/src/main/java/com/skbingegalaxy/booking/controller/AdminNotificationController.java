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

@RestController
@RequestMapping("/api/v1/bookings/admin/notifications")
@RequiredArgsConstructor
public class AdminNotificationController {

    private final AdminNotificationService service;

    /** Paginated notifications visible to the calling admin/super-admin. */
    @GetMapping
    public ResponseEntity<ApiResponse<Page<AdminNotificationDto>>> list(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
            service.list(userId, role, PageRequest.of(page, Math.min(size, 50)))));
    }

    /** Bell-icon badge counter — count of unread notifications for the caller. */
    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<Map<String, Long>>> unreadCount(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role) {
        long count = service.unreadCount(userId, role);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("unread", count)));
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
        int updated = service.markAllRead(userId, role);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("updated", updated)));
    }

    // ── Messaging (two-way inbox) ────────────────────────────────────────────

    /** Messages the caller has SENT (the "Sent" folder). */
    @GetMapping("/sent")
    public ResponseEntity<ApiResponse<Page<AdminNotificationDto>>> sent(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(service.listSent(userId, PageRequest.of(page, Math.min(size, 50)))));
    }

    /** Full conversation thread (oldest-first). */
    @GetMapping("/thread/{threadId}")
    public ResponseEntity<ApiResponse<java.util.List<AdminNotificationDto>>> thread(
            @PathVariable Long threadId,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.ok(service.getThread(threadId, userId, role)));
    }

    /** Compose a new message to a super-admin (role), a specific admin, or a customer. */
    @PostMapping("/send")
    public ResponseEntity<ApiResponse<AdminNotificationDto>> send(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role,
            @RequestBody SendMessageRequest body) {
        AdminNotificationDto dto = service.sendMessage(
            userId, role, body.getSenderName(),
            body.getRecipientUserId(), body.getRecipientRole(), body.getRecipientName(),
            body.getTitle(), body.getMessage(),
            new AdminNotificationService.Attachment(body.getAttachmentUrl(), body.getAttachmentType(), body.getAttachmentName()));
        return ResponseEntity.ok(ApiResponse.ok("Message sent", dto));
    }

    /** Compose one message to multiple specific recipients (admins/super-admins/customers). */
    @PostMapping("/send-bulk")
    public ResponseEntity<ApiResponse<java.util.List<AdminNotificationDto>>> sendBulk(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role,
            @RequestBody SendBulkRequest body) {
        java.util.List<AdminNotificationService.RecipientRef> recipients =
            body.getRecipients() == null ? java.util.List.of()
                : body.getRecipients().stream()
                    .map(r -> new AdminNotificationService.RecipientRef(
                        r.getRecipientUserId(), r.getRecipientRole(), r.getRecipientName()))
                    .toList();
        java.util.List<AdminNotificationDto> sent = service.sendBulk(
            userId, role, body.getSenderName(), recipients, body.getTitle(), body.getMessage(),
            new AdminNotificationService.Attachment(body.getAttachmentUrl(), body.getAttachmentType(), body.getAttachmentName()));
        return ResponseEntity.ok(ApiResponse.ok("Message sent to " + sent.size() + " recipient(s)", sent));
    }

    /** Reply within a conversation — routes back to the other party. */
    @PostMapping("/{id}/reply")
    public ResponseEntity<ApiResponse<AdminNotificationDto>> reply(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role,
            @RequestBody ReplyRequest body) {
        AdminNotificationDto dto = service.reply(id, userId, role, body.getSenderName(), body.getMessage(),
            new AdminNotificationService.Attachment(body.getAttachmentUrl(), body.getAttachmentType(), body.getAttachmentName()));
        return ResponseEntity.ok(ApiResponse.ok("Reply sent", dto));
    }

    /** Delete a single message/notification the caller owns. */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role) {
        service.delete(id, userId, role);
        return ResponseEntity.ok(ApiResponse.ok("Deleted", null));
    }

    /** Clear all already-read inbox items for the caller. */
    @PostMapping("/clear-read")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> clearRead(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role) {
        int removed = service.clearRead(userId, role);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("removed", removed)));
    }

    @lombok.Data
    public static class SendMessageRequest {
        private Long recipientUserId;   // null → role-wide broadcast
        private String recipientRole;   // SUPER_ADMIN | ADMIN | CUSTOMER
        private String recipientName;   // display label
        private String senderName;      // caller's display label
        private String title;
        private String message;
        private String attachmentUrl;
        private String attachmentType;  // image | video
        private String attachmentName;
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
    public static class SendBulkRequest {
        private java.util.List<Recipient> recipients;
        private String senderName;
        private String title;
        private String message;
        private String attachmentUrl;
        private String attachmentType;
        private String attachmentName;

        @lombok.Data
        public static class Recipient {
            private Long recipientUserId;   // required for a specific person
            private String recipientRole;   // SUPER_ADMIN | ADMIN | CUSTOMER
            private String recipientName;   // display label
        }
    }
}
