package com.skbingegalaxy.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.booking.entity.IdempotencyKey;
import com.skbingegalaxy.booking.repository.IdempotencyKeyRepository;
import com.skbingegalaxy.common.exception.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Covers the Stripe-style idempotency contract:
 * <ul>
 *   <li>Blank key => work always runs, nothing persisted.</li>
 *   <li>Miss => work runs once, response cached.</li>
 *   <li>Hit (same payload) => work does NOT run again, cached value returned.</li>
 *   <li>Mismatch (same key, different payload) => 409 Conflict.</li>
 *   <li>Expired row => treated as miss, re-runs and replaces the row.</li>
 * </ul>
 */
class IdempotencyServiceTest {

    private IdempotencyKeyRepository repository;
    private IdempotencyService service;
    private ObjectMapper mapper;

    record Req(String name) {}
    record Res(String ref) {}

    @BeforeEach
    void setup() {
        repository = mock(IdempotencyKeyRepository.class);
        mapper = new ObjectMapper();
        service = new IdempotencyService(repository, mapper, new SimpleMeterRegistry());
        // reflect in the @Value-defaulted field + the claim-first self proxy
        try {
            var f = IdempotencyService.class.getDeclaredField("ttlHours");
            f.setAccessible(true);
            f.setInt(service, 24);
            var s = IdempotencyService.class.getDeclaredField("self");
            s.setAccessible(true);
            s.set(service, service);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void blankKey_runsWorkWithoutPersistence() {
        AtomicInteger calls = new AtomicInteger();
        Res out = service.execute("", "POST", "/api/v1/bookings", 7L,
            new Req("a"), Res.class, () -> { calls.incrementAndGet(); return new Res("REF1"); });

        assertThat(out.ref()).isEqualTo("REF1");
        assertThat(calls.get()).isEqualTo(1);
        verify(repository, never()).save(any());
        verify(repository, never()).findByIdempotencyKeyAndHttpMethodAndRequestPathAndUserId(
            anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void miss_claimsFirst_runsWork_andPersistsResponse() {
        // Claim-first (NEW-2): the in-progress claim is inserted BEFORE the
        // work runs; the completion pass then fills the response into it.
        var stored = new java.util.concurrent.atomic.AtomicReference<IdempotencyKey>();
        when(repository.saveAndFlush(any(IdempotencyKey.class)))
            .thenAnswer(i -> { stored.set(i.getArgument(0)); return i.getArgument(0); });
        when(repository.findByIdempotencyKeyAndHttpMethodAndRequestPathAndUserId(
            eq("k1"), eq("POST"), eq("/api/v1/bookings"), eq(7L)))
            .thenAnswer(i -> Optional.ofNullable(stored.get()));

        Res out = service.execute("k1", "POST", "/api/v1/bookings", 7L,
            new Req("a"), Res.class, () -> {
                // The claim must already be durable while the work runs.
                assertThat(stored.get()).isNotNull();
                assertThat(stored.get().getResponseBody()).isNull();
                return new Res("REF1");
            });

        assertThat(out.ref()).isEqualTo("REF1");
        // Claim row shape
        assertThat(stored.get().getIdempotencyKey()).isEqualTo("k1");
        assertThat(stored.get().getHttpMethod()).isEqualTo("POST");
        assertThat(stored.get().getUserId()).isEqualTo(7L);
        assertThat(stored.get().getRequestHash()).hasSize(64); // sha256 hex
        // Completion pass updated the SAME row with the cached response + full TTL.
        ArgumentCaptor<IdempotencyKey> captor = ArgumentCaptor.forClass(IdempotencyKey.class);
        verify(repository).save(captor.capture());
        IdempotencyKey completed = captor.getValue();
        assertThat(completed.getResponseStatus()).isEqualTo(200);
        assertThat(completed.getResponseBody()).contains("REF1");
        assertThat(completed.getExpiresAt()).isAfter(LocalDateTime.now(ZoneOffset.UTC).plusHours(23));
    }

    @Test
    void simultaneousDuplicate_claimCollision_returns409InProgress() {
        // Two in-flight requests with the same key: the second one's claim
        // insert collides on the composite PK while the winner is still
        // working (row present, body null) → 409, work NOT run.
        IdempotencyKey winnersClaim = IdempotencyKey.builder()
            .idempotencyKey("k1").httpMethod("POST").requestPath("/api/v1/bookings").userId(7L)
            .requestHash("h".repeat(64))
            .responseBody(null)
            .expiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5))
            .build();
        // First lookup: nothing visible yet (winner's claim not yet committed
        // from this transaction's view) → we try to claim ourselves.
        when(repository.findByIdempotencyKeyAndHttpMethodAndRequestPathAndUserId(
            eq("k1"), eq("POST"), eq("/api/v1/bookings"), eq(7L)))
            .thenReturn(Optional.empty(), Optional.of(winnersClaim));
        when(repository.saveAndFlush(any(IdempotencyKey.class)))
            .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));

        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> service.execute("k1", "POST", "/api/v1/bookings", 7L,
                new Req("a"), Res.class, () -> { calls.incrementAndGet(); return new Res("X"); }))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already being processed");
        assertThat(calls.get()).isZero();
        verify(repository, never()).save(any());
    }

    @Test
    void hit_samePayload_returnsCachedResponse_withoutRunningWork() throws Exception {
        Req payload = new Req("a");
        String hash = sha256Of(payload);
        IdempotencyKey existing = IdempotencyKey.builder()
            .idempotencyKey("k1").httpMethod("POST").requestPath("/api/v1/bookings").userId(7L)
            .requestHash(hash)
            .responseBody(mapper.writeValueAsString(new Res("REF-CACHED")))
            .expiresAt(LocalDateTime.now(ZoneOffset.UTC).plusHours(1))
            .build();
        when(repository.findByIdempotencyKeyAndHttpMethodAndRequestPathAndUserId(
            eq("k1"), eq("POST"), eq("/api/v1/bookings"), eq(7L))).thenReturn(Optional.of(existing));

        AtomicInteger calls = new AtomicInteger();
        Res out = service.execute("k1", "POST", "/api/v1/bookings", 7L,
            payload, Res.class, () -> { calls.incrementAndGet(); return new Res("REF-NEW"); });

        assertThat(out.ref()).isEqualTo("REF-CACHED");
        assertThat(calls.get()).isZero();
        verify(repository, never()).save(any());
    }

    @Test
    void mismatch_samePayloadDifferent_throws409() throws Exception {
        IdempotencyKey existing = IdempotencyKey.builder()
            .idempotencyKey("k1").httpMethod("POST").requestPath("/api/v1/bookings").userId(7L)
            .requestHash(sha256Of(new Req("original")))
            .responseBody(mapper.writeValueAsString(new Res("REF-CACHED")))
            .expiresAt(LocalDateTime.now(ZoneOffset.UTC).plusHours(1))
            .build();
        when(repository.findByIdempotencyKeyAndHttpMethodAndRequestPathAndUserId(
            eq("k1"), eq("POST"), eq("/api/v1/bookings"), eq(7L))).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.execute("k1", "POST", "/api/v1/bookings", 7L,
                new Req("different-payload"), Res.class, () -> new Res("SHOULD-NOT-RUN")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("previously used with a different request payload");
        verify(repository, never()).save(any());
    }

    @Test
    void expiredRow_isTreatedAsMiss_andDeletedThenRecreated() throws Exception {
        IdempotencyKey expired = IdempotencyKey.builder()
            .idempotencyKey("k1").httpMethod("POST").requestPath("/api/v1/bookings").userId(7L)
            .requestHash(sha256Of(new Req("a")))
            .responseBody(mapper.writeValueAsString(new Res("OLD")))
            .expiresAt(LocalDateTime.now(ZoneOffset.UTC).minusHours(1))
            .build();
        when(repository.findByIdempotencyKeyAndHttpMethodAndRequestPathAndUserId(
            eq("k1"), eq("POST"), eq("/api/v1/bookings"), eq(7L))).thenReturn(Optional.of(expired));

        Res out = service.execute("k1", "POST", "/api/v1/bookings", 7L,
            new Req("a"), Res.class, () -> new Res("REF-FRESH"));

        assertThat(out.ref()).isEqualTo("REF-FRESH");
        verify(repository).delete(expired);
        verify(repository).save(any(IdempotencyKey.class));
    }

    @Test
    void keyOver128Chars_throws400() {
        String big = "k".repeat(129);
        assertThatThrownBy(() -> service.execute(big, "POST", "/api/v1/bookings", 7L,
                new Req("a"), Res.class, () -> new Res("REF")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("at most 128 characters");
    }

    private String sha256Of(Object payload) throws Exception {
        String json = mapper.writeValueAsString(payload);
        var md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
