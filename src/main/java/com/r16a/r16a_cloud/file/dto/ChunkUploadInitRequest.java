package com.r16a.r16a_cloud.file.dto;

import com.r16a.r16a_cloud.file.Visibility;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;
import java.util.UUID;

public record ChunkUploadInitRequest(
        @NotNull UUID ownerId,
        UUID parentId,
        @NotBlank @Size(max = 255) String fileName,
        @Min(0) long totalSize,
        Integer partSizeBytes,
        @Size(max = 1000) String description,
        Visibility visibility,
        Set<UUID> sharedWithIds
) {
}
