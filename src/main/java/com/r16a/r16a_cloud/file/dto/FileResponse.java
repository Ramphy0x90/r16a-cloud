package com.r16a.r16a_cloud.file.dto;

import com.r16a.r16a_cloud.file.File;
import com.r16a.r16a_cloud.file.Visibility;
import com.r16a.r16a_cloud.user.User;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public record FileResponse(
        UUID id,
        String name,
        String description,
        String fsPath,
        boolean isDirectory,
        Visibility visibility,
        UUID parentId,
        UUID ownerId,
        Set<UUID> sharedWithIds,
        Instant createdAt,
        Instant updatedAt
) {
    public static FileResponse from(File file) {
        return new FileResponse(
                file.getId(),
                file.getName(),
                file.getDescription(),
                file.getFsPath(),
                file.isDirectory(),
                file.getVisibility(),
                file.getParent() != null ? file.getParent().getId() : null,
                file.getOwner().getId(),
                file.getSharedWith().stream().map(User::getId).collect(Collectors.toSet()),
                file.getCreatedAt(),
                file.getUpdatedAt()
        );
    }
}
