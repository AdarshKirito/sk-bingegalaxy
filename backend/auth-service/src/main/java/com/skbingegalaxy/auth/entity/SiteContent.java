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

    /**
     * NO {@code @Lob} here: the column is plain TEXT holding the JSON string.
     * With PostgreSQL, Hibernate 6 maps {@code @Lob String} to a large-object
     * OID and reads it via {@code getLong()} — every {@code findById} then
     * blew up with "Bad value for type long: {json}", so the Terms pages
     * 500'd and existing rows could never be updated.
     */
    @Column(name = "content_json", nullable = false, columnDefinition = "TEXT")
    private String contentJson;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;
}
