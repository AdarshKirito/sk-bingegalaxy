package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.RoomBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface RoomBlockRepository extends JpaRepository<RoomBlock, Long> {

    List<RoomBlock> findByRoomIdOrderByStartAtAsc(Long roomId);

    List<RoomBlock> findByRoomIdInOrderByStartAtAsc(List<Long> roomIds);

    /**
     * Returns blocks for {@code roomId} that overlap the window
     * {@code [windowStart, windowEnd)}. Overlap rule (half-open):
     * {@code block.startAt < windowEnd AND block.endAt > windowStart}.
     */
    @Query("""
        SELECT b FROM RoomBlock b
        WHERE b.roomId = :roomId
          AND b.startAt < :windowEnd
          AND b.endAt   > :windowStart
        """)
    List<RoomBlock> findOverlapping(
        @Param("roomId") Long roomId,
        @Param("windowStart") LocalDateTime windowStart,
        @Param("windowEnd") LocalDateTime windowEnd
    );
}
