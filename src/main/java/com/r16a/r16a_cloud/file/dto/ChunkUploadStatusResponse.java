package com.r16a.r16a_cloud.file.dto;

public record ChunkUploadStatusResponse(long receivedBytes, long totalSize, int partSizeBytes) {
}
