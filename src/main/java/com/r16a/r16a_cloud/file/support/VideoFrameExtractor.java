package com.r16a.r16a_cloud.file.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ws.schild.jave.MultimediaObject;
import ws.schild.jave.ScreenExtractor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;

@Slf4j
@Component
public class VideoFrameExtractor {

    private static final int MAX_CONCURRENT = 2;
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

        try {
            tempOutput = Files.createTempFile("frame_", ".jpg");
            MultimediaObject source = new MultimediaObject(videoPath.toFile());
            long durationMs = source.getInfo().getDuration();
            long seekMs = durationMs > 1000 ? 1000 : 0;

            ScreenExtractor extractor = new ScreenExtractor();
            extractor.renderOneImage(source, -1, -1, seekMs, tempOutput.toFile(), 1);

            byte[] bytes = Files.readAllBytes(tempOutput);
            return bytes.length > 0 ? bytes : null;
        } catch (Exception ex) {
            log.warn("Failed to extract frame from {}: {}", videoPath.getFileName(), ex.getMessage());
            return null;
        } finally {
            if (tempOutput != null) {
                try {
                    Files.deleteIfExists(tempOutput);
                } catch (IOException ignored) {
                }
            }
        }
    }
}
