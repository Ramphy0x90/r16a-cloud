package com.r16a.r16a_cloud.file.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ws.schild.jave.process.ffmpeg.DefaultFFMPEGLocator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Converts HEIC/HEIF images to JPEG using the ffmpeg binary bundled by jave-nativebin-linux64.
 * Called before ImageIO processing since Java's ImageIO has no native HEIC support.
 */
@Slf4j
@Component
public class HeicConverter {

    private static final int TIMEOUT_SECONDS = 30;

    private final String ffmpegPath;

    public HeicConverter() {
        this.ffmpegPath = new DefaultFFMPEGLocator().getExecutablePath();
    }

    public byte[] convertToJpeg(Path heicPath) {
        Path tempOutput = null;
        try {
            tempOutput = Files.createTempFile("heic_", ".jpg");
            Process process = new ProcessBuilder(List.of(
                    ffmpegPath,
                    "-y",
                    "-i", heicPath.toAbsolutePath().toString(),
                    "-vframes", "1",
                    "-q:v", "2",
                    tempOutput.toAbsolutePath().toString()
            ))
                    .redirectErrorStream(true)
                    .start();

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("ffmpeg timed out converting HEIC: {}", heicPath.getFileName());
                return null;
            }
            if (process.exitValue() != 0) {
                log.warn("ffmpeg exited {} converting HEIC: {}", process.exitValue(), heicPath.getFileName());
                return null;
            }

            byte[] bytes = Files.readAllBytes(tempOutput);
            return bytes.length > 0 ? bytes : null;
        } catch (IOException | InterruptedException ex) {
            log.warn("Failed to convert HEIC {}: {}", heicPath.getFileName(), ex.getMessage());
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        } finally {
            if (tempOutput != null) {
                try { Files.deleteIfExists(tempOutput); } catch (IOException ignored) {}
            }
        }
    }
}
