package com.r16a.r16a_cloud.file;

import com.r16a.r16a_cloud.file.dto.*;
import com.r16a.r16a_cloud.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/fs")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    @PostMapping
    public ResponseEntity<FileResponse> createFile(@Valid @RequestBody CreateFileRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(fileService.createFile(request));
    }

    @PostMapping("/upload/init")
    public ResponseEntity<ChunkUploadInitResponse> initChunkedUpload(
            @Valid @RequestBody ChunkUploadInitRequest request,
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                fileService.initChunkedUpload(request, user.getId())
        );
    }

    @PutMapping(value = "/upload/{uploadId}/part", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Void> uploadChunk(
            @PathVariable UUID uploadId,
            @AuthenticationPrincipal User user,
            InputStream body
    ) {
        fileService.uploadChunk(uploadId, user.getId(), body);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/upload/{uploadId}/status")
    public ResponseEntity<ChunkUploadStatusResponse> getChunkedUploadStatus(
            @PathVariable UUID uploadId,
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(fileService.getChunkedUploadStatus(uploadId, user.getId()));
    }

    @PostMapping("/upload/{uploadId}/complete")
    public ResponseEntity<FileResponse> completeChunkedUpload(
            @PathVariable UUID uploadId,
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                fileService.completeChunkedUpload(uploadId, user.getId())
        );
    }

    @DeleteMapping("/upload/{uploadId}")
    public ResponseEntity<Void> cancelChunkedUpload(
            @PathVariable UUID uploadId,
            @AuthenticationPrincipal User user
    ) {
        fileService.cancelChunkedUpload(uploadId, user.getId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/upload")
    public ResponseEntity<FileResponse> uploadFile(
            @RequestParam UUID ownerId,
            @RequestParam(required = false) UUID parentId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) Visibility visibility,
            @RequestParam(required = false) Set<UUID> sharedWithIds
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                fileService.uploadFile(ownerId, parentId, file, description, visibility, sharedWithIds)
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<FileResponse> getFile(@PathVariable UUID id) {
        return ResponseEntity.ok(fileService.getFileById(id));
    }

    @GetMapping
    public ResponseEntity<CursorPageResponse<FileResponse>> getFiles(
            @RequestParam UUID ownerId,
            @RequestParam(required = false) UUID parentId,
            @RequestParam(defaultValue = "name") String sort,
            @RequestParam(defaultValue = "asc") String dir,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor,
            WebRequest webRequest
    ) {
        String eTag = fileService.getFolderETag(ownerId, parentId);
        if (webRequest.checkNotModified(eTag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).build();
        }

        int clampedLimit = Math.max(1, Math.min(200, limit));
        CursorPageResponse<FileResponse> page = fileService.getFilesCursorPage(ownerId, parentId, sort, dir, cursor, clampedLimit);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .eTag(eTag)
                .body(page);
    }

    @GetMapping("/shared-with-me")
    public ResponseEntity<Page<FileResponse>> getFilesSharedWithMe(
            @AuthenticationPrincipal User user,
            Pageable pageable
    ) {
        return ResponseEntity.ok(fileService.getFilesSharedWithUser(user.getId(), pageable));
    }

    @GetMapping("/dashboard")
    public ResponseEntity<DashboardResponse> getDashboard(@RequestParam UUID ownerId) {
        return ResponseEntity.ok(fileService.getDashboard(ownerId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<FileResponse> updateFile(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateFileRequest request
    ) {
        return ResponseEntity.ok(fileService.updateFile(id, request));
    }

    @PatchMapping("/{id}/sharing")
    public ResponseEntity<FileResponse> updateFileSharing(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateFileSharingRequest request
    ) {
        return ResponseEntity.ok(fileService.updateFileSharing(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFile(@PathVariable UUID id) {
        fileService.deleteFile(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<StreamingResponseBody> downloadFile(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader
    ) {
        FileService.DownloadPayload payload = fileService.downloadSingle(id);
        if (rangeHeader != null && payload.sourcePath() != null) {
            return buildRangeResponse(payload, rangeHeader);
        }

        return buildDownloadResponse(payload);
    }

    @GetMapping("/{id}/thumbnail")
    public ResponseEntity<byte[]> downloadThumbnail(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "small") String size,
            WebRequest webRequest
    ) {
        FileService.ThumbnailSize thumbnailSize = FileService.ThumbnailSize.fromQueryValue(size);
        FileService.ThumbnailPayload payload = fileService.downloadThumbnail(id, thumbnailSize);

        if (webRequest.checkNotModified(payload.eTag(), payload.lastModifiedEpochMs())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).build();
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .eTag(payload.eTag())
                .lastModified(payload.lastModifiedEpochMs())
                .contentType(MediaType.parseMediaType(payload.contentType()))
                .body(payload.content());
    }

    @PostMapping("/download")
    public ResponseEntity<StreamingResponseBody> downloadFiles(@Valid @RequestBody DownloadFilesRequest request) {
        FileService.DownloadPayload payload = fileService.downloadMultiple(request.ids());
        return buildDownloadResponse(payload);
    }

    /**
     * Issues a short-lived (5 min) signed download token for a single file.
     */
    @GetMapping("/{id}/download-token")
    public ResponseEntity<Map<String, String>> getDownloadToken(
            @PathVariable UUID id,
            @AuthenticationPrincipal User user
    ) {
        String token = fileService.generateDownloadToken(id, user.getId());
        return ResponseEntity.ok(Map.of("token", token));
    }

    /**
     * Token-authenticated download — no bearer token required (used for direct browser links).
     */
    @GetMapping("/download/token")
    public ResponseEntity<StreamingResponseBody> downloadByToken(
            @RequestParam String token,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader
    ) {
        FileService.DownloadPayload payload = fileService.downloadByToken(token);
        if (rangeHeader != null && payload.sourcePath() != null) {
            return buildRangeResponse(payload, rangeHeader);
        }
        return buildDownloadResponse(payload);
    }

    /**
     * Returns file events since the given epoch-ms cursor for delta sync.
     */
    @GetMapping("/events")
    public ResponseEntity<FileEventsResponse> getEvents(
            @RequestParam UUID ownerId,
            @RequestParam(defaultValue = "0") long since,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return ResponseEntity.ok(fileService.getEventsSince(ownerId, since, limit));
    }

    private ResponseEntity<StreamingResponseBody> buildDownloadResponse(FileService.DownloadPayload payload) {
        String contentDisposition = org.springframework.http.ContentDisposition
                .attachment()
                .filename(payload.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString();

        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(payload.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .header(HttpHeaders.ACCEPT_RANGES, payload.sourcePath() != null ? "bytes" : "none");

        if (payload.contentLength() >= 0) {
            builder = builder.contentLength(payload.contentLength());
        }

        return builder.body(payload.body());
    }

    private ResponseEntity<StreamingResponseBody> buildRangeResponse(FileService.DownloadPayload payload, String rangeHeader) {
        long total = payload.contentLength();

        if (!rangeHeader.startsWith("bytes=")) {
            return buildDownloadResponse(payload);
        }

        String[] parts = rangeHeader.substring(6).split("-", 2);
        long start, end;
        try {
            if (parts[0].isEmpty()) {
                long suffix = Long.parseLong(parts[1]);
                start = Math.max(0, total - suffix);
                end = total - 1;
            } else if (parts.length < 2 || parts[1].isEmpty()) {
                start = Long.parseLong(parts[0]);
                end = total - 1;
            } else {
                start = Long.parseLong(parts[0]);
                end = Long.parseLong(parts[1]);
            }
        } catch (NumberFormatException ex) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + total)
                    .build();
        }

        if (start < 0 || end >= total || start > end) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + total)
                    .build();
        }

        long rangeLength = end - start + 1;
        Path sourcePath = payload.sourcePath();
        final long finalStart = start;
        final long finalLength = rangeLength;

        StreamingResponseBody body = outputStream -> {
            try (FileChannel channel = FileChannel.open(sourcePath, StandardOpenOption.READ)) {
                channel.position(finalStart);
                long remaining = finalLength;
                ByteBuffer buffer = ByteBuffer.allocate(65536);
                while (remaining > 0) {
                    buffer.clear();
                    if (remaining < buffer.capacity()) buffer.limit((int) remaining);
                    int read = channel.read(buffer);
                    if (read <= 0) break;
                    buffer.flip();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    outputStream.write(bytes);
                    remaining -= read;
                }
            } catch (IOException ex) {
                throw new RuntimeException("Failed to stream file range", ex);
            }
        };

        String contentDisposition = org.springframework.http.ContentDisposition
                .attachment()
                .filename(payload.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString();

        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentType(MediaType.parseMediaType(payload.contentType()))
                .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + total)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .contentLength(rangeLength)
                .body(body);
    }
}
