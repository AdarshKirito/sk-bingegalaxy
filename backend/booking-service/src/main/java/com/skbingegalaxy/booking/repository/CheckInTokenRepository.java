package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.CheckInToken;
import com.skbingegalaxy.booking.entity.CheckInToken.TokenType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CheckInTokenRepository extends JpaRepository<CheckInToken, Long> {

    /** Pessimistic lock so concurrent verifies cannot both consume the same token. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM CheckInToken t WHERE t.tokenValue = :value AND t.tokenType = :type")
    Optional<CheckInToken> findByValueAndTypeForUpdate(@Param("value") String value,
                                                       @Param("type") TokenType type);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM CheckInToken t WHERE t.bookingRef = :ref AND t.tokenType = :type AND t.consumedAt IS NULL ORDER BY t.issuedAt DESC")
    List<CheckInToken> findActiveByBookingRefForUpdate(@Param("ref") String bookingRef,
                                                       @Param("type") TokenType type);

    /** Used by the OTP issue path to invalidate any prior un-consumed OTPs for this booking. */
    @Query("SELECT t FROM CheckInToken t WHERE t.bookingRef = :ref AND t.tokenType = :type AND t.consumedAt IS NULL")
    List<CheckInToken> findActiveByBookingRefAndType(@Param("ref") String bookingRef,
                                                     @Param("type") TokenType type);

    /** Cleanup helper: tokens past expiry that were never consumed. */
    long deleteByExpiresAtBeforeAndConsumedAtIsNull(LocalDateTime cutoff);
}
