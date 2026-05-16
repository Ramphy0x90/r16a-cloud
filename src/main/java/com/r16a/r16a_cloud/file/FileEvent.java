package com.r16a.r16a_cloud.file;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "file_events",
    indexes = {
        @Index(name = "idx_file_events_owner_occurred", columnList = "owner_id, occurred_at")
    }
)
public class FileEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "file_id", nullable = false)
    private UUID fileId;

    @Column(nullable = false)
    private String fileName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FileEventType eventType;

    @Column(nullable = false)
    private Instant occurredAt;
}
