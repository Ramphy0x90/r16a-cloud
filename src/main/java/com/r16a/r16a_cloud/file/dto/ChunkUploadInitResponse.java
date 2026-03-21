package com.r16a.r16a_cloud.file.dto;

import java.util.UUID;

public record ChunkUploadInitResponse(UUID uploadId, int partSizeBytes) {
}
