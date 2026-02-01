package com.r16a.r16a_cloud.file.dto;

import com.r16a.r16a_cloud.file.Visibility;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record UpdateFileRequest(
        @Size(max = 255) String name,
        @Size(max = 1000) String description,
        Long parentId,
        Visibility visibility,
        Set<Long> sharedWithIds
) {
}
