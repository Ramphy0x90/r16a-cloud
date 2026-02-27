package com.r16a.r16a_cloud.file.dto;

import com.r16a.r16a_cloud.file.Visibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;
import java.util.UUID;

public record CreateFileRequest(
        @NotBlank
        @Size(max = 255)
        String name,

        @Size(max = 1000)
        String description,

        @NotNull
        UUID ownerId,

        UUID parentId,
        boolean isDirectory,
        Visibility visibility,
        Set<UUID> sharedWithIds
) {
}
