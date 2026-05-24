package com.r16a.r16a_cloud.file.support;

import com.r16a.r16a_cloud.exception.ResourceNotFoundException;
import com.r16a.r16a_cloud.exception.StorageException;
import com.r16a.r16a_cloud.file.File;
import com.r16a.r16a_cloud.file.FileRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class ThumbnailService {

    private final FileRepository fileRepository;

    @Value("${app.upload.path}")
    private String uploadRootPath;

    @PostConstruct
    void initThumbnailsDir() {
        try {
            Files.createDirectories(thumbnailsDir());
        } catch (IOException ex) {
            throw new StorageException(
                    "Failed to initialize thumbnails directory: " + ex.getMessage(), ex
            );
        }
    }

    @Cacheable(value = "thumbnails", key = "#id + ':' + #size.queryValue()")
    public ThumbnailPayload downloadThumbnail(UUID id, ThumbnailSize size) {
        File file = fileRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("File", "id", id));

        if (file.isDirectory()) {
            throw new StorageException("Cannot generate thumbnail for a directory.");
        }

        Path path = Path.of(file.getFsPath());
        if (!Files.exists(path) || Files.isDirectory(path)) {
            throw new StorageException("File content is unavailable: " + file.getName());
        }

        String contentType;
        try {
            contentType = Files.probeContentType(path);
        } catch (IOException ex) {
            throw new StorageException("Failed to read file metadata: " + file.getName(), ex);
        }

        if (contentType == null || contentType.isBlank()) contentType = "application/octet-stream";
        if (!contentType.startsWith("image/")) {
            throw new StorageException("Thumbnails are only supported for image files.");
        }

        long lastModifiedEpochMs = file.getUpdatedAt().toEpochMilli();

        String outputFormat = resolveOutputFormatFromContentType(contentType);
        Path cachePath = thumbnailCachePath(id, size, outputFormat);
        if (Files.exists(cachePath)) {
            try {
                byte[] cached = Files.readAllBytes(cachePath);
                String cachedType = resolveContentTypeFromOutputFormat(outputFormat);
                return new ThumbnailPayload(cachedType, cached, lastModifiedEpochMs, buildThumbnailETag(id, size, lastModifiedEpochMs, cached.length));
            } catch (IOException ex) {
                log.warn("Failed to read thumbnail cache for {}, regenerating", id);
            }
        }

        try {
            byte[] originalContent = Files.readAllBytes(path);
            ThumbnailBinary tb = tryBuildThumbnail(originalContent, contentType, size.maxDimensionPx());

            try {
                Files.write(cachePath, tb.content());
            } catch (IOException ex) {
                log.warn("Failed to write thumbnail cache for {}: {}", id, ex.getMessage());
            }

            return new ThumbnailPayload(tb.contentType(), tb.content(), lastModifiedEpochMs, buildThumbnailETag(id, size, lastModifiedEpochMs, tb.content().length));
        } catch (IOException ex) {
            throw new StorageException("Failed to generate thumbnail for file: " + file.getName(), ex);
        }
    }

    public String computeBlurHash(Path path) {
        String contentType;
        try {
            contentType = Files.probeContentType(path);
        } catch (IOException ex) {
            return null;
        }

        if (contentType == null || !contentType.startsWith("image/") || isVectorOrUnsupportedForResize(contentType)) {
            return null;
        }

        try (InputStream in = Files.newInputStream(path)) {
            BufferedImage image = ImageIO.read(in);
            if (image == null) return null;
            return BlurHashEncoder.encode(image, 4, 3);
        } catch (Exception ex) {
            log.warn("Failed to compute BlurHash for {}: {}", path, ex.getMessage());
            return null;
        }
    }

    public void deleteThumbnailCache(UUID fileId) {
        Path dir = thumbnailsDir();
        if (!Files.isDirectory(dir)) return;
        String prefix = fileId.toString();

        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(p -> p.getFileName().toString().startsWith(prefix))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ex) {
                            log.warn("Failed to delete thumbnail cache file {}: {}", p, ex.getMessage());
                        }
                    });
        } catch (IOException ex) {
            log.warn("Failed to list thumbnail cache dir while cleaning file {}: {}", fileId, ex.getMessage());
        }
    }

    private Path thumbnailsDir() {
        return Path.of(uploadRootPath, ".thumbnails");
    }

    private Path thumbnailCachePath(UUID fileId, ThumbnailSize size, String format) {
        return thumbnailsDir().resolve(fileId + "_" + size.queryValue() + "." + format);
    }

    private String buildThumbnailETag(UUID id, ThumbnailSize size, long lastModifiedEpochMs, int contentLength) {
        return "\"" + id + ":" + size.queryValue() + ":" + lastModifiedEpochMs + ":" + contentLength + "\"";
    }

    private String resolveOutputFormatFromContentType(String contentType) {
        return switch (contentType.toLowerCase()) {
            case "image/jpeg", "image/jpg", "image/bmp" -> "jpg";
            case "image/gif" -> "gif";
            default -> "png";
        };
    }

    private ThumbnailBinary tryBuildThumbnail(byte[] originalContent, String contentType, int maxDimensionPx) {
        if (isVectorOrUnsupportedForResize(contentType)) {
            return new ThumbnailBinary(originalContent, contentType);
        }

        try (ByteArrayInputStream input = new ByteArrayInputStream(originalContent);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            BufferedImage source = ImageIO.read(input);
            if (source == null) {
                return new ThumbnailBinary(originalContent, contentType);
            }

            int sourceWidth = source.getWidth();
            int sourceHeight = source.getHeight();
            int largestDimension = Math.max(sourceWidth, sourceHeight);
            if (largestDimension <= maxDimensionPx) {
                return new ThumbnailBinary(originalContent, contentType);
            }

            double ratio = (double) maxDimensionPx / largestDimension;
            int targetWidth = Math.max(1, (int) Math.round(sourceWidth * ratio));
            int targetHeight = Math.max(1, (int) Math.round(sourceHeight * ratio));
            int bufferedType = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
            BufferedImage resized = new BufferedImage(targetWidth, targetHeight, bufferedType);

            Graphics2D graphics = resized.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
            } finally {
                graphics.dispose();
            }

            String outputFormat = resolveOutputFormat(contentType, resized.getColorModel().hasAlpha());
            boolean writeOk = ImageIO.write(resized, outputFormat, output);
            if (!writeOk) {
                return new ThumbnailBinary(originalContent, contentType);
            }

            return new ThumbnailBinary(output.toByteArray(), resolveContentTypeFromOutputFormat(outputFormat));
        } catch (IOException ex) {
            log.warn("Failed to resize image, returning original content.", ex);
            return new ThumbnailBinary(originalContent, contentType);
        }
    }

    private boolean isVectorOrUnsupportedForResize(String contentType) {
        return "image/svg+xml".equalsIgnoreCase(contentType) || "image/avif".equalsIgnoreCase(contentType);
    }

    private String resolveOutputFormat(String contentType, boolean hasAlpha) {
        if ("image/jpeg".equalsIgnoreCase(contentType) || "image/jpg".equalsIgnoreCase(contentType)) {
            return "jpg";
        }
        if ("image/bmp".equalsIgnoreCase(contentType)) {
            return "bmp";
        }
        if ("image/gif".equalsIgnoreCase(contentType)) {
            return "gif";
        }
        if ("image/png".equalsIgnoreCase(contentType) || "image/webp".equalsIgnoreCase(contentType) || hasAlpha) {
            return "png";
        }
        return "jpg";
    }

    private String resolveContentTypeFromOutputFormat(String outputFormat) {
        return switch (outputFormat.toLowerCase()) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            default -> "application/octet-stream";
        };
    }

    public record ThumbnailPayload(
            String contentType,
            byte[] content,
            long lastModifiedEpochMs,
            String eTag
    ) implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
    }

    private record ThumbnailBinary(byte[] content, String contentType) {
    }

    public enum ThumbnailSize {
        SMALL("small", 200),
        MEDIUM("medium", 512);

        private final String queryValue;
        private final int maxDimensionPx;

        ThumbnailSize(String queryValue, int maxDimensionPx) {
            this.queryValue = queryValue;
            this.maxDimensionPx = maxDimensionPx;
        }

        public String queryValue() {
            return queryValue;
        }

        public int maxDimensionPx() {
            return maxDimensionPx;
        }

        public static ThumbnailSize fromQueryValue(String raw) {
            if (raw == null || raw.isBlank()) {
                return SMALL;
            }
            for (ThumbnailSize value : values()) {
                if (value.queryValue.equalsIgnoreCase(raw)) {
                    return value;
                }
            }
            throw new StorageException("Unsupported thumbnail size: " + raw);
        }
    }
}
