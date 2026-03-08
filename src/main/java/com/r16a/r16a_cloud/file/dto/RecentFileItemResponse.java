package com.r16a.r16a_cloud.file.dto;

import com.r16a.r16a_cloud.file.File;
import com.r16a.r16a_cloud.file.Visibility;

import java.time.Instant;
import java.util.UUID;

public record RecentFileItemResponse(
        UUID id,
        String name,
        Visibility visibility,
        long sizeBytes,
        Instant updatedAt
) {
    public static RecentFileItemResponse from(File file) {
        return new RecentFileItemResponse(
                file.getId(),
                file.getName(),
                file.getVisibility(),
                file.getSizeBytes(),
                file.getUpdatedAt()
        );
    }
}
