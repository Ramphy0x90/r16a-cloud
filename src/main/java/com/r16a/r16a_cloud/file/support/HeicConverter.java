package com.r16a.r16a_cloud.file.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Converts HEIC/HEIF images to JPEG using heif-thumbnailer (libheif 1.19).
 *
 * Why heif-thumbnailer and not ffmpeg:
 * - The jave-bundled ffmpeg (4.4 static) has no libheif at all.
 * - System ffmpeg 7.x can decode HEIC but only outputs individual tiles (512x512)
 *   from an iPhone Tile Grid, not the assembled full image.
 * - heif-thumbnailer uses libheif's native HEIC decoder and with -p renders the
 *   primary full-resolution image (e.g. 4032x3024) correctly.
 *
 * heif-thumbnailer outputs PNG; this class converts the PNG to JPEG via ImageIO
 * so callers get a byte[] they can pass straight into the thumbnail pipeline.
 *
 * Semaphore(2) + tryAcquire(): at most 2 concurrent conversions. If at capacity
 * the request returns null and the client retries on the next cache miss.
 */
@Slf4j
@Component
public class HeicConverter {

    private static final int MAX_CONCURRENT = 2;
    private static final int TIMEOUT_SECONDS = 60;

    private final Semaphore semaphore = new Semaphore(MAX_CONCURRENT);

    public byte[] convertToJpeg(Path heicPath) {
        if (!semaphore.tryAcquire()) {
            log.debug("HEIC conversion at capacity, skipping {}", heicPath.getFileName());
            return null;
        }
        try {
            return doConvert(heicPath);
        } finally {
            semaphore.release();
        }
    }

    private byte[] doConvert(Path heicPath) {
        Path tempPng = null;
        try {
            tempPng = Files.createTempFile("heic_", ".png");

            // heif-thumbnailer -p: render from primary image (not the stored embedded thumbnail)
            // -s 4096: allow up to 4096px on the long edge — large enough for any phone photo
            Process process = new ProcessBuilder(List.of(
                    "heif-thumbnailer",
                    "-p",
                    "-s", "4096",
                    heicPath.toAbsolutePath().toString(),
                    tempPng.toAbsolutePath().toString()
            ))
                    .redirectErrorStream(true)
                    .start();

            process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("heif-thumbnailer timed out for {}", heicPath.getFileName());
                return null;
            }
            if (process.exitValue() != 0) {
                log.warn("heif-thumbnailer exited {} for {}", process.exitValue(), heicPath.getFileName());
                return null;
            }

            // heif-thumbnailer always writes PNG — convert to JPEG so the rest of the
            // thumbnail pipeline (ImageIO resize) gets a format it can handle uniformly
            BufferedImage image = ImageIO.read(tempPng.toFile());
            if (image == null) {
                log.warn("Could not read PNG output from heif-thumbnailer for {}", heicPath.getFileName());
                return null;
            }
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                ImageIO.write(image, "jpg", out);
                return out.toByteArray();
            }

        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("HEIC conversion failed for {}: {}", heicPath.getFileName(), ex.getMessage());
            return null;
        } finally {
            if (tempPng != null) {
                try { Files.deleteIfExists(tempPng); } catch (IOException ignored) {}
            }
        }
    }
}
