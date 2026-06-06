package com.r16a.r16a_cloud.file.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class VideoFrameExtractor {

    private static final int MAX_CONCURRENT = 2;
    private static final int TIMEOUT_SECONDS = 60;

    private final Semaphore semaphore = new Semaphore(MAX_CONCURRENT);

    public byte[] extractFrame(Path videoPath) {
        if (!semaphore.tryAcquire()) {
            log.debug("Video frame extraction at capacity, skipping {}", videoPath.getFileName());
            return null;
        }
        try {
            return doExtract(videoPath);
        } finally {
            semaphore.release();
        }
    }

    private byte[] doExtract(Path videoPath) {
        Path tempOutput = null;
        Process process = null;
        try {
            tempOutput = Files.createTempFile("frame_", ".jpg");
            process = new ProcessBuilder(List.of(
                    "ffmpeg",
                    "-ss", "1",
                    "-i", videoPath.toAbsolutePath().toString(),
                    "-vframes", "1",
                    "-threads", "1",
                    "-update", "1",
                    "-y",
                    tempOutput.toAbsolutePath().toString()
            ))
                    .redirectErrorStream(true)
                    .start();

            // drain stdout/stderr so the process doesn't block on a full pipe buffer
            try (InputStream ignored = process.getInputStream()) {
                ignored.transferTo(java.io.OutputStream.nullOutputStream());
            }

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("ffmpeg timed out extracting frame from {}", videoPath.getFileName());
                return null;
            }
            if (process.exitValue() != 0) {
                log.warn("ffmpeg exited {} for {}", process.exitValue(), videoPath.getFileName());
                return null;
            }

            byte[] bytes = Files.readAllBytes(tempOutput);
            return bytes.length > 0 ? bytes : null;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Failed to extract frame from {}: {}", videoPath.getFileName(), ex.getMessage());
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (tempOutput != null) {
                try {
                    Files.deleteIfExists(tempOutput);
                } catch (IOException ignored) {
                }
            }
        }
    }
}
