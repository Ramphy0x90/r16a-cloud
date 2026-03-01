package com.r16a.r16a_cloud.file;

import com.r16a.r16a_cloud.file.dto.CreateFileRequest;
import com.r16a.r16a_cloud.file.dto.DownloadFilesRequest;
import com.r16a.r16a_cloud.file.dto.FileResponse;
import com.r16a.r16a_cloud.file.dto.UpdateFileRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
    public ResponseEntity<Page<FileResponse>> getFiles(
            @RequestParam UUID ownerId,
            @RequestParam(required = false) UUID parentId,
            Pageable pageable
    ) {
        return ResponseEntity.ok(fileService.getFiles(ownerId, parentId, pageable));
    }

    @PutMapping("/{id}")
    public ResponseEntity<FileResponse> updateFile(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateFileRequest request
    ) {
        return ResponseEntity.ok(fileService.updateFile(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFile(@PathVariable UUID id) {
        fileService.deleteFile(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> downloadFile(@PathVariable UUID id) {
        FileService.DownloadPayload payload = fileService.downloadSingle(id);
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
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate().mustRevalidate())
                .eTag(payload.eTag())
                .lastModified(payload.lastModifiedEpochMs())
                .contentType(MediaType.parseMediaType(payload.contentType()))
                .body(payload.content());
    }

    @PostMapping("/download")
    public ResponseEntity<byte[]> downloadFiles(@Valid @RequestBody DownloadFilesRequest request) {
        FileService.DownloadPayload payload = fileService.downloadMultiple(request.ids());
        return buildDownloadResponse(payload);
    }

    private ResponseEntity<byte[]> buildDownloadResponse(FileService.DownloadPayload payload) {
        String contentDisposition = org.springframework.http.ContentDisposition
                .attachment()
                .filename(payload.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(payload.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .body(payload.content());
    }
}
