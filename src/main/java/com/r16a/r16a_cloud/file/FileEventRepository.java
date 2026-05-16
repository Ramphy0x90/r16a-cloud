package com.r16a.r16a_cloud.file;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.UUID;

public interface FileEventRepository extends JpaRepository<FileEvent, UUID> {

    Slice<FileEvent> findByOwnerIdAndOccurredAtGreaterThanOrderByOccurredAtAsc(
            UUID ownerId, Instant since, Pageable pageable);
}
