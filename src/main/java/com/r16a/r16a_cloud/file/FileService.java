package com.r16a.r16a_cloud.file;

import com.r16a.r16a_cloud.exception.ResourceAlreadyExistsException;
import com.r16a.r16a_cloud.exception.ResourceNotFoundException;
import com.r16a.r16a_cloud.exception.StorageException;
import com.r16a.r16a_cloud.file.dto.*;
import com.r16a.r16a_cloud.user.User;
import com.r16a.r16a_cloud.user.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    private final FileRepository fileRepository;
    private final UserRepository userRepository;
    private final FileEventRepository fileEventRepository;
    private final DownloadTokenService downloadTokenService;
    private final ThumbnailService thumbnailService;
    private final FileZipService fileZipService;

    @Value("${app.upload.path}")
    private String uploadRootPath;

    @PostConstruct
    void initUploadRoot() {
        try {
            Files.createDirectories(Path.of(uploadRootPath));
        } catch (IOException ex) {
            throw new StorageException(
                    "Failed to initialize upload root '" + uploadRootPath + "': " + ex.getMessage(),
                    ex
            );
        }
    }

    public FileResponse getFileById(UUID id) {
        return FileResponse.from(findFileOrThrow(id));
    }

    public Page<FileResponse> getFiles(UUID ownerId, UUID parentId, Pageable pageable) {
        if (!userRepository.existsById(ownerId)) {
            throw new ResourceNotFoundException("User", "id", ownerId);
        }

        Page<File> files;
        if (parentId != null) {
            findFileOrThrow(parentId);
            files = fileRepository.findByParentIdAndOwnerId(parentId, ownerId, pageable);
        } else {
            files = fileRepository.findByParentIsNullAndOwnerId(ownerId, pageable);
        }

        return files.map(FileResponse::from);
    }

    public Page<FileResponse> getFilesSharedWithUser(UUID userId, Pageable pageable) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User", "id", userId);
        }

        return fileRepository.findFilesSharedWithUser(userId, pageable).map(FileResponse::from);
    }

    public DashboardResponse getDashboard(UUID ownerId) {
        if (!userRepository.existsById(ownerId)) {
            throw new ResourceNotFoundException("User", "id", ownerId);
        }

        long uploadedFiles = fileRepository.countByOwnerIdAndIsDirectoryFalse(ownerId);
        long usedStorageBytes = fileRepository.sumFileSizeBytesByOwnerId(ownerId);
        long sharedFiles = fileRepository.countSharedFilesByOwnerId(ownerId);
        List<RecentFileItemResponse> recentFiles = fileRepository
                .findTop5ByOwnerIdAndIsDirectoryFalseOrderByUpdatedAtDesc(ownerId)
                .stream()
                .map(RecentFileItemResponse::from)
                .toList();

        DashboardMetricsResponse metrics = new DashboardMetricsResponse(uploadedFiles, usedStorageBytes, sharedFiles);
        return new DashboardResponse(metrics, recentFiles);
    }

    @Transactional
    public FileResponse createFile(CreateFileRequest request) {
        User owner = userRepository.findById(request.ownerId())
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", request.ownerId()));

        File parent = resolveParentForOwner(request.parentId(), owner.getId());
        checkDuplicateNameForCreate(request.name(), parent, owner.getId());

        Path targetPath = resolveTargetPath(owner.getId(), parent, request.name());
        createFsEntry(targetPath, request.isDirectory());

        File file = File.builder()
                .name(request.name())
                .description(request.description())
                .fsPath(targetPath.toString())
                .isDirectory(request.isDirectory())
                .sizeBytes(0L)
                .visibility(request.visibility() != null ? request.visibility() : Visibility.PRIVATE)
                .parent(parent)
                .owner(owner)
                .sharedWith(resolveUsers(request.sharedWithIds()))
                .build();

        try {
            FileResponse response = FileResponse.from(fileRepository.save(file));
            recordEvent(file, FileEventType.CREATED);
            return response;
        } catch (RuntimeException ex) {
            rollbackFsEntry(targetPath);
            throw ex;
        }
    }

    @Transactional
    public FileResponse uploadFile(
            UUID ownerId,
            UUID parentId,
            MultipartFile upload,
            String description,
            Visibility visibility,
            Set<UUID> sharedWithIds
    ) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", ownerId));

        String fileName = sanitizeFilename(upload);
        File parent = resolveParentForOwner(parentId, ownerId);
        checkDuplicateNameForCreate(fileName, parent, ownerId);

        Path targetPath = resolveTargetPath(ownerId, parent, fileName);
        writeUploadedFile(upload, targetPath);

        String blurHash = thumbnailService.computeBlurHash(targetPath);

        File file = File.builder()
                .name(fileName)
                .description(description)
                .fsPath(targetPath.toString())
                .isDirectory(false)
                .sizeBytes(upload.getSize())
                .visibility(visibility != null ? visibility : Visibility.PRIVATE)
                .parent(parent)
                .owner(owner)
                .sharedWith(resolveUsers(sharedWithIds))
                .blurHash(blurHash)
                .build();

        try {
            FileResponse response = FileResponse.from(fileRepository.save(file));
            recordEvent(file, FileEventType.CREATED);
            return response;
        } catch (RuntimeException ex) {
            rollbackFsEntry(targetPath);
            throw ex;
        }
    }

    @Transactional
    public FileResponse updateFile(UUID id, UpdateFileRequest request) {
        File file = findFileOrThrow(id);
        String targetName = request.name() != null ? request.name() : file.getName();

        File targetParent = file.getParent();
        if (request.parentId() != null) {
            targetParent = resolveParentForOwner(request.parentId(), file.getOwner().getId());
            validateDirectoryMove(file, targetParent);
        }

        boolean locationChanged = !Objects.equals(targetName, file.getName())
                || !Objects.equals(
                targetParent != null ? targetParent.getId() : null,
                file.getParent() != null ? file.getParent().getId() : null
        );

        Path oldPath = Path.of(file.getFsPath());
        Path newPath = oldPath;

        if (locationChanged) {
            checkDuplicateNameForUpdate(targetName, targetParent, file.getOwner().getId(), file.getId());
            newPath = resolveTargetPath(file.getOwner().getId(), targetParent, targetName);
            moveFsEntry(oldPath, newPath);
        }

        if (request.name() != null) {
            file.setName(targetName);
        }

        if (request.description() != null) {
            file.setDescription(request.description());
        }

        if (request.parentId() != null) {
            file.setParent(targetParent);
        }

        if (locationChanged) {
            String oldFsPath = file.getFsPath();
            file.setFsPath(newPath.toString());

            if (file.isDirectory()) {
                updateDescendantPaths(file, oldFsPath, newPath.toString());
            }
        }

        if (request.visibility() != null) {
            file.setVisibility(request.visibility());
        }

        if (request.sharedWithIds() != null) {
            file.setSharedWith(resolveUsers(request.sharedWithIds()));
        }

        FileResponse response = FileResponse.from(fileRepository.save(file));
        recordEvent(file, FileEventType.UPDATED);
        return response;
    }

    @Transactional
    public FileResponse updateFileSharing(UUID id, UpdateFileSharingRequest request) {
        File file = findFileOrThrow(id);
        Set<User> sharedUsers = resolveUsers(request.sharedWithIds());
        file.setSharedWith(sharedUsers);
        applyVisibilityForSharing(file, sharedUsers);
        return FileResponse.from(fileRepository.save(file));
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "thumbnails", key = "#id + ':small'"),
            @CacheEvict(value = "thumbnails", key = "#id + ':medium'")
    })
    public void deleteFile(UUID id) {
        File file = findFileOrThrow(id);
        recordEvent(file, FileEventType.DELETED);
        deleteFsEntry(Path.of(file.getFsPath()));
        thumbnailService.deleteThumbnailCache(id);
        deleteFromDbRecursively(file);
    }

    public DownloadPayload downloadSingle(UUID id) {
        File file = findFileOrThrow(id);
        if (!file.isDirectory()) {
            return buildSingleFilePayload(file);
        }

        return new DownloadPayload(file.getName() + ".zip", "application/zip", fileZipService.zipFiles(List.of(file)), -1, null);
    }

    public DownloadPayload downloadMultiple(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new StorageException("At least one file id is required for download.");
        }

        List<File> files = ids.stream().map(this::findFileOrThrow).toList();
        if (files.size() == 1 && !files.get(0).isDirectory()) {
            return buildSingleFilePayload(files.get(0));
        }

        String zipName = "download_" + Instant.now().toEpochMilli() + ".zip";
        return new DownloadPayload(zipName, "application/zip", fileZipService.zipFiles(files), -1, null);
    }

    private File findFileOrThrow(UUID id) {
        return fileRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("File", "id", id));
    }

    private File resolveParentForOwner(UUID parentId, UUID ownerId) {
        if (parentId == null) {
            return null;
        }

        File parent = findFileOrThrow(parentId);
        if (!ownerId.equals(parent.getOwner().getId())) {
            throw new ResourceNotFoundException("File", "id", parentId);
        }

        if (!parent.isDirectory()) {
            throw new StorageException("Parent must be a directory.");
        }

        return parent;
    }

    private void checkDuplicateNameForCreate(String name, File parent, UUID ownerId) {
        boolean exists = parent != null
                ? fileRepository.existsByNameAndParentIdAndOwnerId(name, parent.getId(), ownerId)
                : fileRepository.existsByNameAndParentIsNullAndOwnerId(name, ownerId);

        if (exists) {
            throw new ResourceAlreadyExistsException("File", "name", name);
        }
    }

    private void checkDuplicateNameForUpdate(String name, File parent, UUID ownerId, UUID fileId) {
        boolean exists = parent != null
                ? fileRepository.existsByNameAndParentIdAndOwnerIdAndIdNot(name, parent.getId(), ownerId, fileId)
                : fileRepository.existsByNameAndParentIsNullAndOwnerIdAndIdNot(name, ownerId, fileId);

        if (exists) {
            throw new ResourceAlreadyExistsException("File", "name", name);
        }
    }

    private Set<User> resolveUsers(Set<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return new HashSet<>();
        }

        Set<User> users = new HashSet<>(userRepository.findAllById(userIds));
        if (users.size() != userIds.size()) {
            throw new ResourceNotFoundException("User", "ids", userIds);
        }

        return users;
    }

    private void applyVisibilityForSharing(File file, Set<User> sharedUsers) {
        if (!sharedUsers.isEmpty()) {
            file.setVisibility(Visibility.SHARED);
            return;
        }

        if (file.getVisibility() == Visibility.SHARED) {
            file.setVisibility(Visibility.PRIVATE);
        }
    }

    private Path resolveTargetPath(UUID ownerId, File parent, String name) {
        Path ownerRoot = resolveOwnerRoot(ownerId);
        Path target = parent != null
                ? Path.of(parent.getFsPath()).resolve(name).normalize()
                : ownerRoot.resolve(name).normalize();

        if (!target.startsWith(ownerRoot)) {
            throw new StorageException("Resolved path escapes user root: " + target);
        }

        return target;
    }

    private Path resolveOwnerRoot(UUID ownerId) {
        Path ownerRoot = Path.of(uploadRootPath).resolve("user_" + ownerId).normalize();

        try {
            Files.createDirectories(ownerRoot);
        } catch (IOException ex) {
            throw new StorageException(
                    "Failed to create owner root '" + ownerRoot + "': " + ex.getMessage(),
                    ex
            );
        }

        return ownerRoot;
    }

    private void createFsEntry(Path targetPath, boolean isDirectory) {
        try {
            Files.createDirectories(targetPath.getParent());
            if (isDirectory) {
                Files.createDirectories(targetPath);
            } else {
                Files.createFile(targetPath);
            }
        } catch (IOException ex) {
            throw new StorageException(
                    "Failed to create filesystem entry: " + targetPath + " (" + ex.getMessage() + ")",
                    ex
            );
        }
    }

    private void writeUploadedFile(MultipartFile upload, Path targetPath) {
        try {
            Files.createDirectories(targetPath.getParent());
            try (InputStream in = upload.getInputStream()) {
                Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new StorageException(
                    "Failed to write uploaded file: " + targetPath + " (" + ex.getMessage() + ")",
                    ex
            );
        }
    }

    private String sanitizeFilename(MultipartFile upload) {
        return sanitizeFilenameFromString(upload.getOriginalFilename());
    }

    private String sanitizeFilenameFromString(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            throw new StorageException("Uploaded file name is invalid.");
        }

        String fileName = Path.of(originalName).getFileName().toString().trim();
        if (fileName.isBlank()) {
            throw new StorageException("Uploaded file name is invalid.");
        }

        return fileName;
    }

    private void moveFsEntry(Path source, Path target) {
        try {
            Files.createDirectories(target.getParent());
            Files.move(source, target);
        } catch (IOException ex) {
            throw new StorageException(
                    "Failed to move filesystem entry from " + source + " to " + target,
                    ex
            );
        }
    }

    private void deleteFsEntry(Path targetPath) {
        if (!Files.exists(targetPath)) {
            return;
        }

        try (var stream = Files.walk(targetPath)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    throw new StorageException("Failed to delete path: " + path, ex);
                }
            });
        } catch (IOException ex) {
            throw new StorageException("Failed to traverse path for deletion: " + targetPath, ex);
        }
    }

    private void rollbackFsEntry(Path targetPath) {
        try {
            if (Files.isDirectory(targetPath)) {
                deleteFsEntry(targetPath);
            } else {
                Files.deleteIfExists(targetPath);
            }
        } catch (RuntimeException | IOException ex) {
            log.warn("Failed to rollback filesystem entry {}", targetPath, ex);
        }
    }

    private void deleteFromDbRecursively(File file) {
        List<File> children = new ArrayList<>(fileRepository.findByParentId(file.getId()));
        for (File child : children) {
            deleteFromDbRecursively(child);
        }
        fileRepository.delete(file);
    }

    private void updateDescendantPaths(File directory, String oldPrefix, String newPrefix) {
        List<File> children = fileRepository.findByParentId(directory.getId());
        for (File child : children) {
            child.setFsPath(child.getFsPath().replaceFirst("^" + java.util.regex.Pattern.quote(oldPrefix), newPrefix));
            fileRepository.save(child);
            if (child.isDirectory()) {
                updateDescendantPaths(child, oldPrefix, newPrefix);
            }
        }
    }

    private void validateDirectoryMove(File file, File targetParent) {
        if (targetParent == null) {
            return;
        }

        if (file.getId().equals(targetParent.getId())) {
            throw new StorageException("Cannot move a directory into itself.");
        }

        if (!file.isDirectory()) {
            return;
        }

        File cursor = targetParent;
        while (cursor != null) {
            if (cursor.getId().equals(file.getId())) {
                throw new StorageException("Cannot move a directory into one of its descendants.");
            }

            cursor = cursor.getParent();
        }
    }

    private DownloadPayload buildSingleFilePayload(File file) {
        Path path = Path.of(file.getFsPath());

        if (!Files.exists(path) || Files.isDirectory(path)) {
            throw new StorageException("File content is unavailable: " + file.getName());
        }

        String contentType;
        long contentLength;
        try {
            contentType = Files.probeContentType(path);
            contentLength = Files.size(path);
        } catch (IOException ex) {
            throw new StorageException("Failed to read file metadata for download: " + file.getName(), ex);
        }

        if (contentType == null || contentType.isBlank()) {
            contentType = "application/octet-stream";
        }

        StreamingResponseBody body = outputStream -> {
            try {
                Files.copy(path, outputStream);
            } catch (IOException ex) {
                throw new StorageException("Failed to stream file: " + file.getName(), ex);
            }
        };

        return new DownloadPayload(file.getName(), contentType, body, contentLength, path);
    }


    // ── Download tokens ───────────────────────────────────────────────────────

    public String generateDownloadToken(UUID fileId, UUID requesterId) {
        findFileOrThrow(fileId); // verify file exists
        return downloadTokenService.generateToken(fileId, requesterId);
    }

    public DownloadPayload downloadByToken(String token) {
        UUID fileId = downloadTokenService.validateToken(token);
        return downloadSingle(fileId);
    }

    // ── File event changelog ──────────────────────────────────────────────────

    private void recordEvent(File file, FileEventType type) {
        try {
            FileEvent event = FileEvent.builder()
                    .ownerId(file.getOwner().getId())
                    .parentId(file.getParent() != null ? file.getParent().getId() : null)
                    .fileId(file.getId())
                    .fileName(file.getName())
                    .eventType(type)
                    .occurredAt(Instant.now())
                    .build();
            fileEventRepository.save(event);
        } catch (Exception ex) {
            log.warn("Failed to record file event for {} ({}): {}", file.getId(), type, ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public FileEventsResponse getEventsSince(UUID ownerId, long sinceEpochMs, int limit) {
        Instant since = Instant.ofEpochMilli(sinceEpochMs);
        Pageable p = PageRequest.of(0, Math.min(limit, 500));
        Slice<FileEvent> slice = fileEventRepository
                .findByOwnerIdAndOccurredAtGreaterThanOrderByOccurredAtAsc(ownerId, since, p);

        List<FileEventDto> events = slice.getContent().stream()
                .map(FileEventDto::from)
                .toList();

        long nextCursor = slice.getContent().isEmpty()
                ? sinceEpochMs
                : slice.getContent().get(slice.getContent().size() - 1).getOccurredAt().toEpochMilli();

        return new FileEventsResponse(events, nextCursor, slice.hasNext());
    }

    public String getFolderETag(UUID ownerId, UUID parentId) {
        Instant maxUpdatedAt = parentId != null
                ? fileRepository.findMaxUpdatedAtByOwnerIdAndParentId(ownerId, parentId).orElse(Instant.EPOCH)
                : fileRepository.findMaxUpdatedAtByOwnerIdAndParentIsNull(ownerId).orElse(Instant.EPOCH);
        return "\"" + ownerId + ":" + (parentId != null ? parentId : "root") + ":" + maxUpdatedAt.toEpochMilli() + "\"";
    }

    /**
     * sourcePath is non-null for single files (enables range requests); null for zip archives.
     */
    public record DownloadPayload(String fileName, String contentType, StreamingResponseBody body, long contentLength,
                                  Path sourcePath) {
    }
}
