package com.r16a.r16a_cloud.file;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ChunkUploadCleanupTask {

    private final ChunkedUploadService chunkedUploadService;

    @Scheduled(fixedRateString = "${app.upload.chunk-cleanup-interval-ms:3600000}")
    public void cleanupExpiredChunkSessions() {
        chunkedUploadService.deleteExpiredChunkUploadSessions();
    }
}
