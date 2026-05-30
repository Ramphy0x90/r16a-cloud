package com.r16a.r16a_cloud.file.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class VideoFrameExtractor {

    private static final int TIMEOUT_SECONDS = 30;

    /**
     * Extracts a single frame from a video file using ffmpeg and returns it as PNG bytes.
     * Tries 1s into the video first; falls back to the very first frame if that fails
     * (handles videos shorter than 1 second).
     *
     * Returns null if ffmpeg is not installed or extraction fails.
     */
    public byte[] extractFrame(Path videoPath) {
        byte[] frame = runFfmpeg(videoPath, "00:00:01");
        if (frame == null) {
            frame = runFfmpeg(videoPath, "00:00:00");
        }
        return frame;
    }

    private byte[] runFfmpeg(Path videoPath, String timestamp) {
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg",
                "-ss", timestamp,
                "-i", videoPath.toAbsolutePath().toString(),
                "-frames:v", "1",
                "-f", "image2pipe",
                "-vcodec", "png",
                "-"
        );
        pb.redirectErrorStream(false);

        Process process;
        try {
            process = pb.start();
        } catch (IOException ex) {
            log.warn("ffmpeg not available, video thumbnails disabled: {}", ex.getMessage());
            return null;
        }

        try {
            byte[] frameBytes;
            try (InputStream stdout = process.getInputStream()) {
                frameBytes = stdout.readAllBytes();
            }

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("ffmpeg timed out extracting frame from {}", videoPath.getFileName());
                return null;
            }

            if (process.exitValue() != 0 || frameBytes.length == 0) {
                return null;
            }

            return frameBytes;
        } catch (IOException | InterruptedException ex) {
            process.destroyForcibly();
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Failed to extract frame from {}: {}", videoPath.getFileName(), ex.getMessage());
            return null;
        }
    }
}
