package com.r16a.r16a_cloud.file;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ChunkUploadCleanupTask {

    private final FileService fileService;

    @Scheduled(fixedRateString = "${app.upload.chunk-cleanup-interval-ms:3600000}")
    public void cleanupExpiredChunkSessions() {
        fileService.deleteExpiredChunkUploadSessions();
    }
}
