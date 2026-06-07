package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.dto.BookingTransferDto;
import com.skbingegalaxy.booking.dto.TransferBookingRequest;
import com.skbingegalaxy.booking.entity.Booking;
import com.skbingegalaxy.booking.entity.BookingTransfer;
import com.skbingegalaxy.booking.entity.OutboxEvent;
import com.skbingegalaxy.booking.repository.BookingRepository;
import com.skbingegalaxy.booking.repository.BookingTransferRepository;
import com.skbingegalaxy.booking.repository.OutboxEventRepository;
import com.skbingegalaxy.common.constants.KafkaTopics;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.enums.BookingStatus;
import com.skbingegalaxy.common.enums.NotificationChannel;
import com.skbingegalaxy.common.event.NotificationEvent;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

/**
 * 2-phase booking-transfer flow. The original endpoint
 * {@link BookingService#transferBooking(String, Long, TransferBookingRequest)}
 * is the single-step admin/legacy path; this service is the customer-facing
 * "request → recipient accepts → ownership flips" path.
 *
 * <p>Why 2-phase:
 * <ul>
 *   <li>Recipient consent — prevents griefing where a malicious owner offloads
 *       a booking on someone who didn't ask for it.</li>
 *   <li>Anti-abuse — caps the number of transfer requests per customer in a
 *       rolling 30-day window.</li>
 *   <li>Expiry — pending transfers auto-cancel if not handled, releasing the
 *       slot for the original owner to keep or re-transfer.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingTransferService {

    private final BookingRepository bookingRepository;
    private final BookingTransferRepository transferRepository;
    private final BookingService bookingService;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final BookingEventPublisher bookingEventPublisher;

    /** Public base URL of the recipient-facing accept page. The accept token is appended. */
    @Value("${app.booking.transfer-accept-url:https://app.skbingegalaxy.com/transfers}")
    private String transferAcceptBaseUrl;

    /** Hours a PENDING transfer offer remains valid before the scheduler expires it. */
    @Value("${app.booking.transfer-request-ttl-hours:48}")
    private int transferRequestTtlHours;

    /** Maximum transfer requests a customer can create in a rolling 30-day window. */
    @Value("${app.booking.transfer-abuse-limit:5}")
    private int transferAbuseLimitPer30Days;

    private static final SecureRandom RANDOM = new SecureRandom();

    // ── Customer: request a transfer ──────────────────────────────────────
    @Transactional
    public BookingTransferDto requestTransfer(String bookingRef, Long customerId,
                                              String customerName, String customerEmail,
                                              TransferBookingRequest request) {
        Booking booking = bookingRepository.findByBookingRefAndBingeId(
                bookingRef, BingeContext.requireBingeId())
            .orElseThrow(() -> new ResourceNotFoundException("Booking", "ref", bookingRef));

        if (!booking.getCustomerId().equals(customerId)) {
            throw new BusinessException("Not authorised to transfer this booking", HttpStatus.FORBIDDEN);
        }
        if (booking.getStatus() != BookingStatus.PENDING && booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new BusinessException(
                "Only PENDING or CONFIRMED bookings can be transferred. Current status: " + booking.getStatus());
        }
        if (booking.isTransferred()) {
            throw new BusinessException(
                "This booking has already been transferred once. Further transfers are not allowed.");
        }
        if (booking.getCustomerEmail() != null
                && booking.getCustomerEmail().equalsIgnoreCase(request.getRecipientEmail())) {
            throw new BusinessException("Cannot transfer a booking to yourself");
        }

        // Reject if a PENDING transfer already exists for this booking (the DB
        // unique partial index will also enforce this; we surface a friendlier error).
        transferRepository.findFirstByBookingRefAndStatus(
                bookingRef, BookingTransfer.Status.PENDING)
            .ifPresent(existing -> {
                throw new BusinessException(
                    "A transfer offer is already pending for this booking. Revoke it before sending another.",
                    HttpStatus.CONFLICT);
            });

        // Anti-abuse: rolling 30-day count
        long recent = transferRepository.countByFromCustomerIdAndCreatedAtAfter(
            customerId, LocalDateTime.now(ZoneOffset.UTC).minusDays(30));
        if (recent >= transferAbuseLimitPer30Days) {
            throw new BusinessException(
                "Transfer limit reached: maximum " + transferAbuseLimitPer30Days
                    + " transfer requests per 30 days.",
                HttpStatus.TOO_MANY_REQUESTS);
        }

        BookingTransfer transfer = BookingTransfer.builder()
            .bookingRef(bookingRef)
            .bingeId(booking.getBingeId())
            .fromCustomerId(customerId)
            .fromCustomerName(customerName != null ? customerName : booking.getCustomerName())
            .fromCustomerEmail(customerEmail != null ? customerEmail : booking.getCustomerEmail())
            .toName(request.getRecipientName())
            .toEmail(request.getRecipientEmail())
            .toPhone(request.getRecipientPhone())
            .toPhoneCountryCode(request.getRecipientPhoneCountryCode())
            .status(BookingTransfer.Status.PENDING)
            .acceptToken(generateToken())
            .expiresAt(LocalDateTime.now(ZoneOffset.UTC).plusHours(transferRequestTtlHours))
            .build();
        transfer = transferRepository.save(transfer);

        publishOutbox("TRANSFER_REQUEST_SENT", transfer);
        log.info("Transfer requested: bookingRef={} from={} to={} expiresAt={}",
            bookingRef, customerEmail, request.getRecipientEmail(), transfer.getExpiresAt());

        return toDto(transfer);
    }

    // ── Recipient: accept the transfer ────────────────────────────────────
    /**
     * Token-based accept. The recipient does NOT need a logged-in account;
     * the token is a single-use opaque bearer delivered via email. If the
     * recipient is signed in, pass the optional {@code recipientCustomerId}
     * for audit/linkage.
     */
    @Transactional
    public BookingTransferDto acceptTransfer(String token, Long recipientCustomerId) {
        BookingTransfer transfer = transferRepository.findByAcceptToken(token)
            .orElseThrow(() -> new BusinessException(
                "Invalid or unknown transfer token", HttpStatus.NOT_FOUND));

        ensurePending(transfer);
        if (transfer.getExpiresAt().isBefore(LocalDateTime.now(ZoneOffset.UTC))) {
            transfer.setStatus(BookingTransfer.Status.EXPIRED);
            transferRepository.save(transfer);
            throw new BusinessException(
                "This transfer offer has expired", HttpStatus.GONE);
        }

        // Reuse the existing single-step transfer logic to apply the ownership
        // flip atomically — re-runs all status/cutoff/already-transferred guards.
        TransferBookingRequest req = TransferBookingRequest.builder()
            .recipientName(transfer.getToName())
            .recipientEmail(transfer.getToEmail())
            .recipientPhone(transfer.getToPhone())
            .recipientPhoneCountryCode(transfer.getToPhoneCountryCode())
            .build();

        // Tenancy: the booking is in transfer.bingeId. The public accept endpoint
        // doesn't carry an X-Binge-Id header (it's reached via an opaque email
        // link), so we set the context from the transfer row before calling the
        // booking-side mutation. The controller is responsible for clearing it.
        BingeContext.setBingeId(transfer.getBingeId());

        bookingService.transferBooking(transfer.getBookingRef(), transfer.getFromCustomerId(), req);

        transfer.setStatus(BookingTransfer.Status.ACCEPTED);
        transfer.setAcceptedAt(LocalDateTime.now(ZoneOffset.UTC));
        transfer.setToCustomerId(recipientCustomerId);
        transfer = transferRepository.save(transfer);

        publishOutbox("TRANSFER_REQUEST_ACCEPTED", transfer);
        log.info("Transfer accepted: bookingRef={} by recipientEmail={} customerId={}",
            transfer.getBookingRef(), transfer.getToEmail(), recipientCustomerId);
        return toDto(transfer);
    }

    // ── Recipient: decline the transfer ───────────────────────────────────
    @Transactional
    public BookingTransferDto declineTransfer(String token, String reason) {
        BookingTransfer transfer = transferRepository.findByAcceptToken(token)
            .orElseThrow(() -> new BusinessException(
                "Invalid or unknown transfer token", HttpStatus.NOT_FOUND));
        ensurePending(transfer);

        transfer.setStatus(BookingTransfer.Status.DECLINED);
        transfer.setDeclinedAt(LocalDateTime.now(ZoneOffset.UTC));
        transfer.setDeclineReason(reason);
        transfer = transferRepository.save(transfer);

        publishOutbox("TRANSFER_REQUEST_DECLINED", transfer);
        log.info("Transfer declined: bookingRef={} reason={}", transfer.getBookingRef(), reason);
        return toDto(transfer);
    }

    // ── Sender: revoke a pending transfer ─────────────────────────────────
    @Transactional
    public BookingTransferDto revokeTransfer(Long transferId, Long customerId) {
        BookingTransfer transfer = transferRepository.findById(transferId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "BookingTransfer", "id", transferId.toString()));

        if (!transfer.getFromCustomerId().equals(customerId)) {
            throw new BusinessException(
                "Only the original sender can revoke this transfer", HttpStatus.FORBIDDEN);
        }
        ensurePending(transfer);

        transfer.setStatus(BookingTransfer.Status.REVOKED);
        transfer.setRevokedAt(LocalDateTime.now(ZoneOffset.UTC));
        transfer = transferRepository.save(transfer);

        publishOutbox("TRANSFER_REQUEST_REVOKED", transfer);
        log.info("Transfer revoked: bookingRef={} by customerId={}",
            transfer.getBookingRef(), customerId);
        return toDto(transfer);
    }

    // ── Read paths ────────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public List<BookingTransferDto> listForBooking(String bookingRef, Long customerId) {
        Booking booking = bookingRepository.findByBookingRefAndBingeId(
                bookingRef, BingeContext.requireBingeId())
            .orElseThrow(() -> new ResourceNotFoundException("Booking", "ref", bookingRef));
        if (!booking.getCustomerId().equals(customerId)
                && (booking.getOriginalCustomerId() == null
                    || !booking.getOriginalCustomerId().equals(customerId))) {
            throw new BusinessException(
                "Not authorised to view transfers for this booking", HttpStatus.FORBIDDEN);
        }
        return transferRepository.findByBookingRefOrderByCreatedAtDesc(bookingRef)
            .stream().map(this::toDto).toList();
    }

    /** Resolve a transfer by its accept-token (used by the public accept-link landing). */
    @Transactional(readOnly = true)
    public BookingTransfer findByToken(String token) {
        return transferRepository.findByAcceptToken(token)
            .orElseThrow(() -> new BusinessException(
                "Invalid or unknown transfer token", HttpStatus.NOT_FOUND));
    }

    // ── Scheduler hook ────────────────────────────────────────────────────
    /** Marks expired PENDING transfers as EXPIRED. Returns number of rows transitioned. */
    @Transactional
    public int expireStalePending() {
        List<BookingTransfer> expired =
            transferRepository.findExpiredPending(LocalDateTime.now(ZoneOffset.UTC));
        for (BookingTransfer t : expired) {
            t.setStatus(BookingTransfer.Status.EXPIRED);
            transferRepository.save(t);
            publishOutbox("TRANSFER_REQUEST_EXPIRED", t);
        }
        if (!expired.isEmpty()) {
            log.info("Expired {} pending booking transfers", expired.size());
        }
        return expired.size();
    }

    // ── Helpers ───────────────────────────────────────────────────────────
    private void ensurePending(BookingTransfer t) {
        if (t.getStatus() != BookingTransfer.Status.PENDING) {
            throw new BusinessException(
                "Transfer is no longer pending. Current status: " + t.getStatus(),
                HttpStatus.CONFLICT);
        }
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void publishOutbox(String eventType, BookingTransfer t) {
        try {
            // Recipient is the audience for SENT; original sender is the audience
            // for ACCEPTED / DECLINED / EXPIRED / REVOKED. Choose accordingly.
            boolean toRecipient = "TRANSFER_REQUEST_SENT".equals(eventType)
                || "TRANSFER_REQUEST_ACCEPTED".equals(eventType);
            String email = toRecipient ? t.getToEmail()       : t.getFromCustomerEmail();
            String name  = toRecipient ? t.getToName()        : t.getFromCustomerName();
            String phone = toRecipient ? t.getToPhone()       : null;
            String phoneCc = toRecipient ? t.getToPhoneCountryCode() : null;

            String acceptLink = transferAcceptBaseUrl
                + (transferAcceptBaseUrl.endsWith("/") ? "" : "/")
                + t.getAcceptToken();

            String subject;
            String body;
            switch (eventType) {
                case "TRANSFER_REQUEST_SENT":
                    subject = t.getFromCustomerName() + " wants to transfer a booking to you";
                    body = String.format(
                        "Hi %s,%n%n%s (%s) is offering to transfer booking %s to you.%n"
                            + "Open this link to accept or decline: %s%n%n"
                            + "This offer expires on %s. If you don't recognise this, you can safely ignore it.",
                        name, t.getFromCustomerName(), t.getFromCustomerEmail(),
                        t.getBookingRef(), acceptLink, t.getExpiresAt());
                    break;
                case "TRANSFER_REQUEST_ACCEPTED":
                    subject = "Booking " + t.getBookingRef() + " has been transferred to you";
                    body = String.format(
                        "Hi %s,%n%nThe transfer for booking %s has been completed."
                            + " You are now the registered guest.",
                        name, t.getBookingRef());
                    break;
                case "TRANSFER_REQUEST_DECLINED":
                    subject = "Your transfer offer for " + t.getBookingRef() + " was declined";
                    body = String.format(
                        "Hi %s,%n%n%s declined the transfer offer for booking %s."
                            + " You remain the registered guest.",
                        name, t.getToName(), t.getBookingRef());
                    break;
                case "TRANSFER_REQUEST_REVOKED":
                    subject = "Transfer offer for " + t.getBookingRef() + " withdrawn";
                    body = String.format(
                        "Hi %s,%n%nThe transfer offer for booking %s was withdrawn by the sender.",
                        t.getToName(), t.getBookingRef());
                    // Revoke notifies the recipient that the offer is gone.
                    email = t.getToEmail();
                    name = t.getToName();
                    phone = t.getToPhone();
                    phoneCc = t.getToPhoneCountryCode();
                    break;
                case "TRANSFER_REQUEST_EXPIRED":
                    subject = "Transfer offer for " + t.getBookingRef() + " expired";
                    body = String.format(
                        "Hi %s,%n%nThe transfer offer for booking %s expired before %s could accept it."
                            + " You remain the registered guest.",
                        name, t.getBookingRef(), t.getToName());
                    break;
                default:
                    subject = "Booking transfer update";
                    body = "Booking " + t.getBookingRef() + ": " + eventType;
            }

            NotificationEvent ev = NotificationEvent.builder()
                .type(eventType)
                .channel(NotificationChannel.EMAIL)
                .recipientEmail(email)
                .recipientName(name)
                .recipientPhone(phone)
                .recipientPhoneCountryCode(phoneCc)
                .subject(subject)
                .body(body)
                .bookingRef(t.getBookingRef())
                .metadata(java.util.Map.of(
                    "transferId", String.valueOf(t.getId()),
                    "bookingRef", t.getBookingRef(),
                    "bingeId", String.valueOf(t.getBingeId()),
                    "status", t.getStatus().name(),
                    "acceptLink", acceptLink,
                    "expiresAt", t.getExpiresAt().toString()))
                .build();

            // Use the central publisher: it stamps the envelope (eventId,
            // version, correlationId, occurredAt) before serialising. One key
            // per transfer keeps all of its events on the same Kafka partition.
            bookingEventPublisher.publish(KafkaTopics.NOTIFICATION_SEND, "BTR-" + t.getId(), ev);
        } catch (Exception ex) {
            log.warn("Failed to enqueue {} outbox event for transfer {}: {}",
                eventType, t.getId(), ex.getMessage());
        }
    }

    private BookingTransferDto toDto(BookingTransfer t) {
        return BookingTransferDto.builder()
            .id(t.getId())
            .bookingRef(t.getBookingRef())
            .fromCustomerId(t.getFromCustomerId())
            .fromCustomerName(t.getFromCustomerName())
            .fromCustomerEmail(t.getFromCustomerEmail())
            .toName(t.getToName())
            .toEmail(t.getToEmail())
            .toPhone(t.getToPhone())
            .toPhoneCountryCode(t.getToPhoneCountryCode())
            .toCustomerId(t.getToCustomerId())
            .status(t.getStatus())
            .expiresAt(t.getExpiresAt())
            .createdAt(t.getCreatedAt())
            .acceptedAt(t.getAcceptedAt())
            .declinedAt(t.getDeclinedAt())
            .revokedAt(t.getRevokedAt())
            .declineReason(t.getDeclineReason())
            .build();
    }
}
