package com.skbingegalaxy.auth.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "site_content")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class SiteContent {

    @Id
    @Column(length = 64)
    private String slug;

    @Lob
    @Column(name = "content_json", nullable = false, columnDefinition = "TEXT")
    private String contentJson;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;
}
