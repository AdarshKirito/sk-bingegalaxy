package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Per-binge CMS document. Composite primary key (bingeId, slug) so the same
 * slug ("account-page") can carry distinct content for each binge that an
 * admin owns. Mirrors the global SiteContent table in auth-service but
 * scoped to a single binge so the binge owner can self-serve.
 */
@Entity
@Table(name = "binge_site_content")
@IdClass(BingeSiteContent.PK.class)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class BingeSiteContent {

    @Id
    @Column(name = "binge_id")
    private Long bingeId;

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

    /** Composite-PK record for JPA. */
    @NoArgsConstructor
    @AllArgsConstructor
    @Getter @Setter
    public static class PK implements Serializable {
        private Long bingeId;
        private String slug;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK that)) return false;
            return Objects.equals(bingeId, that.bingeId) && Objects.equals(slug, that.slug);
        }

        @Override
        public int hashCode() { return Objects.hash(bingeId, slug); }
    }
}
