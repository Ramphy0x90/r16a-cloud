package com.r16a.r16a_cloud.file;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

public interface FileEventRepository extends JpaRepository<FileEvent, UUID> {

    Slice<FileEvent> findByOwnerIdAndOccurredAtGreaterThanOrderByOccurredAtAsc(
            UUID ownerId, Instant since, Pageable pageable);

    long deleteByOwnerId(UUID ownerId);

    /** Bulk delete for retention: one statement, no entity loading. */
    @Modifying
    @Transactional
    @Query("DELETE FROM FileEvent e WHERE e.occurredAt < :cutoff")
    int deleteOccurredBefore(@Param("cutoff") Instant cutoff);
}
