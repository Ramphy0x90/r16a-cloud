package com.r16a.r16a_cloud.file.dto;

import com.r16a.r16a_cloud.file.FileEvent;

import java.util.UUID;

public record FileEventDto(
        UUID fileId,
        UUID parentId,
        String fileName,
        String eventType,
        long occurredAt
) {
    public static FileEventDto from(FileEvent e) {
        return new FileEventDto(
                e.getFileId(),
                e.getParentId(),
                e.getFileName(),
                e.getEventType().name(),
                e.getOccurredAt().toEpochMilli()
        );
    }
}
