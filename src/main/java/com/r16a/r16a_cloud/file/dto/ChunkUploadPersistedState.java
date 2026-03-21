package com.r16a.r16a_cloud.file.dto;

import com.r16a.r16a_cloud.file.Visibility;

import java.util.Set;
import java.util.UUID;

public record ChunkUploadPersistedState(
        UUID uploadId,
        UUID ownerId,
        UUID parentId,
        String fileName,
        long totalSize,
        int partSizeBytes,
        long receivedBytes,
        long createdAtEpochMs,
        String description,
        Visibility visibility,
        Set<UUID> sharedWithIds
) {
    public ChunkUploadPersistedState withReceivedBytes(long newReceived) {
        return new ChunkUploadPersistedState(
                uploadId,
                ownerId,
                parentId,
                fileName,
                totalSize,
                partSizeBytes,
                newReceived,
                createdAtEpochMs,
                description,
                visibility,
                sharedWithIds
        );
    }
}
