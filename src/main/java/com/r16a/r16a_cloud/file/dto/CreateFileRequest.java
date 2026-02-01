package com.r16a.r16a_cloud.file.dto;

import com.r16a.r16a_cloud.file.Visibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record CreateFileRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 1000) String description,
        @NotNull Long ownerId,
        Long parentId,
        boolean directory,
        Long size,
        String mimeType,
        Visibility visibility,
        Set<Long> sharedWithIds
) {
}
