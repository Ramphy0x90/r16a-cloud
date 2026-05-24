package com.r16a.r16a_cloud.file.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
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
        @JsonProperty("isDirectory") boolean isDirectory,
        Visibility visibility,
        UUID parentId,
        UUID ownerId,
        String ownerDisplayName,
        Set<UUID> sharedWithIds,
        Instant createdAt,
        Instant updatedAt,
        Instant takenAt,
        String blurHash
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
                file.getOwner().getDisplayName(),
                file.getSharedWith().stream().map(User::getId).collect(Collectors.toSet()),
                file.getCreatedAt(),
                file.getUpdatedAt(),
                file.getTakenAt(),
                file.getBlurHash()
        );
    }
}
