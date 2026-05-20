package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.BingeSiteContent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BingeSiteContentRepository extends JpaRepository<BingeSiteContent, BingeSiteContent.PK> {

    Optional<BingeSiteContent> findByBingeIdAndSlug(Long bingeId, String slug);
}
