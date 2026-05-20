package com.skbingegalaxy.booking.dto;

import com.skbingegalaxy.booking.entity.BookingNote;
import lombok.*;

import java.time.LocalDateTime;

@Getter @Setter @Builder
@NoArgsConstructor @AllArgsConstructor
public class BookingNoteDto {
    private Long id;
    private String bookingRef;
    private Long bingeId;
    private Long authorAdminId;
    private String authorName;
    private String body;
    private String visibility;
    private boolean pinned;
    private boolean edited;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static BookingNoteDto from(BookingNote n) {
        return BookingNoteDto.builder()
            .id(n.getId())
            .bookingRef(n.getBookingRef())
            .bingeId(n.getBingeId())
            .authorAdminId(n.getAuthorAdminId())
            .authorName(n.getAuthorName())
            .body(n.getBody())
            .visibility(n.getVisibility().name())
            .pinned(n.isPinned())
            .edited(n.isEdited())
            .createdAt(n.getCreatedAt())
            .updatedAt(n.getUpdatedAt())
            .build();
    }
}
