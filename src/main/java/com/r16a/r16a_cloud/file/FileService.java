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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    private static final JsonMapper CHUNK_SESSION_JSON = JsonMapper.builder().build();

    private final FileRepository fileRepository;
    private final UserRepository userRepository;

    @Value("${app.upload.path}")
    private String uploadRootPath;

    @Value("${app.upload.chunk-default-part-size-bytes:8388608}")
    private int chunkDefaultPartSizeBytes;

    @Value("${app.upload.chunk-min-part-size-bytes:1048576}")
    private int chunkMinPartSizeBytes;

    @Value("${app.upload.chunk-max-part-size-bytes:67108864}")
    private int chunkMaxPartSizeBytes;

    @Value("${app.upload.chunk-session-ttl-hours:48}")
    private long chunkSessionTtlHours;

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
            return FileResponse.from(fileRepository.save(file));
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
                .build();

        try {
            return FileResponse.from(fileRepository.save(file));
        } catch (RuntimeException ex) {
            rollbackFsEntry(targetPath);
            throw ex;
        }
    }

    public ChunkUploadInitResponse initChunkedUpload(ChunkUploadInitRequest request, UUID authenticatedUserId) {
        if (!request.ownerId().equals(authenticatedUserId)) {
            throw new AccessDeniedException("ownerId must match authenticated user");
        }

        if (request.totalSize() < 0) {
            throw new StorageException("totalSize must be non-negative.");
        }

        User owner = userRepository.findById(request.ownerId())
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", request.ownerId()));

        String fileName = sanitizeFilenameFromString(request.fileName());
        File parent = resolveParentForOwner(request.parentId(), request.ownerId());
        checkDuplicateNameForCreate(fileName, parent, request.ownerId());

        int partSizeBytes = resolvePartSizeBytes(request.partSizeBytes());
        if (request.totalSize() > 0 && partSizeBytes <= 0) {
            throw new StorageException("partSizeBytes must be positive when totalSize is positive.");
        }

        UUID uploadId = UUID.randomUUID();
        Path sessionDir = chunkSessionDir(uploadId);
        try {
            Files.createDirectories(sessionDir);
        } catch (IOException ex) {
            throw new StorageException("Failed to create chunk session directory: " + sessionDir, ex);
        }

        Set<UUID> sharedIds = request.sharedWithIds() != null ? request.sharedWithIds() : Set.of();
        ChunkUploadPersistedState state = new ChunkUploadPersistedState(
                uploadId,
                request.ownerId(),
                request.parentId(),
                fileName,
                request.totalSize(),
                partSizeBytes,
                0L,
                System.currentTimeMillis(),
                request.description(),
                request.visibility(),
                sharedIds
        );
        writeChunkSessionState(sessionDir, state);
        return new ChunkUploadInitResponse(uploadId, partSizeBytes);
    }

    public void uploadChunk(UUID uploadId, UUID authenticatedUserId, InputStream body) {
        ChunkUploadPersistedState session = loadChunkSessionOrThrow(uploadId);
        assertChunkOwner(session, authenticatedUserId);

        if (session.totalSize() == 0) {
            throw new StorageException("Cannot upload parts for a zero-byte file; call complete instead.");
        }

        long remaining = session.totalSize() - session.receivedBytes();
        if (remaining <= 0) {
            throw new StorageException("Upload already complete on disk; call complete to finalize.");
        }

        long expectedThisPart = Math.min(session.partSizeBytes(), remaining);
        Path sessionDir = chunkSessionDir(uploadId);
        Path dataPath = sessionDir.resolve("data.bin");
        long confirmedBefore = session.receivedBytes();
        long copied = 0;

        try (OutputStream out = Files.newOutputStream(
                dataPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        )) {
            copied = copyExactStream(body, out, expectedThisPart);
            if (copied != expectedThisPart) {
                throw new StorageException(
                        "Chunk too short: expected " + expectedThisPart + " bytes, got " + copied
                );
            }
            int extra = body.read();
            if (extra >= 0) {
                throw new StorageException("Chunk body is larger than expected for this part.");
            }
        } catch (IOException ex) {
            rollbackPartialChunk(dataPath, confirmedBefore);
            throw new StorageException("Failed to append chunk data: " + ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            rollbackPartialChunk(dataPath, confirmedBefore);
            throw ex;
        }

        long newReceived = confirmedBefore + copied;
        ChunkUploadPersistedState updated = session.withReceivedBytes(newReceived);
        writeChunkSessionState(sessionDir, updated);
    }

    public ChunkUploadStatusResponse getChunkedUploadStatus(UUID uploadId, UUID authenticatedUserId) {
        ChunkUploadPersistedState session = loadChunkSessionOrThrow(uploadId);
        assertChunkOwner(session, authenticatedUserId);
        return new ChunkUploadStatusResponse(session.receivedBytes(), session.totalSize(), session.partSizeBytes());
    }

    @Transactional
    public FileResponse completeChunkedUpload(UUID uploadId, UUID authenticatedUserId) {
        ChunkUploadPersistedState session = loadChunkSessionOrThrow(uploadId);
        assertChunkOwner(session, authenticatedUserId);

        if (session.receivedBytes() != session.totalSize()) {
            throw new StorageException(
                    "Incomplete upload: received " + session.receivedBytes() + " of " + session.totalSize() + " bytes."
            );
        }

        User owner = userRepository.findById(session.ownerId())
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", session.ownerId()));

        File parent = resolveParentForOwner(session.parentId(), session.ownerId());
        checkDuplicateNameForCreate(session.fileName(), parent, session.ownerId());

        Path sessionDir = chunkSessionDir(uploadId);
        Path dataPath = sessionDir.resolve("data.bin");
        Path targetPath = resolveTargetPath(session.ownerId(), parent, session.fileName());

        try {
            Files.createDirectories(targetPath.getParent());
            if (session.totalSize() == 0) {
                createFsEntry(targetPath, false);
            } else {
                if (!Files.isRegularFile(dataPath)) {
                    throw new StorageException("Missing chunk data file for completed upload.");
                }
                if (Files.size(dataPath) != session.totalSize()) {
                    throw new StorageException("Chunk data size mismatch.");
                }
                Files.move(dataPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new StorageException("Failed to finalize chunked upload: " + ex.getMessage(), ex);
        }

        File file = File.builder()
                .name(session.fileName())
                .description(session.description())
                .fsPath(targetPath.toString())
                .isDirectory(false)
                .sizeBytes(session.totalSize())
                .visibility(session.visibility() != null ? session.visibility() : Visibility.PRIVATE)
                .parent(parent)
                .owner(owner)
                .sharedWith(resolveUsers(
                        session.sharedWithIds() != null ? session.sharedWithIds() : Set.of()
                ))
                .build();

        try {
            FileResponse response = FileResponse.from(fileRepository.save(file));
            deleteFsEntry(sessionDir);
            return response;
        } catch (RuntimeException ex) {
            rollbackFsEntry(targetPath);
            throw ex;
        }
    }

    public void cancelChunkedUpload(UUID uploadId, UUID authenticatedUserId) {
        ChunkUploadPersistedState session = loadChunkSessionOrThrow(uploadId);
        assertChunkOwner(session, authenticatedUserId);
        deleteFsEntry(chunkSessionDir(uploadId));
    }

    public void deleteExpiredChunkUploadSessions() {
        Path root = chunkSessionsRoot();
        if (!Files.isDirectory(root)) {
            return;
        }
        long cutoffMs = Instant.now().minus(chunkSessionTtlHours, ChronoUnit.HOURS).toEpochMilli();
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                try {
                    Path sessionFile = dir.resolve("session.json");
                    if (!Files.isRegularFile(sessionFile)) {
                        deleteFsEntry(dir);
                        return;
                    }
                    ChunkUploadPersistedState s = readChunkSessionState(dir);
                    if (s.createdAtEpochMs() < cutoffMs) {
                        deleteFsEntry(dir);
                    }
                } catch (Exception ex) {
                    log.warn("Failed to clean chunk session dir {}: {}", dir, ex.getMessage());
                }
            });
        } catch (IOException ex) {
            log.warn("Failed to list chunk sessions: {}", ex.getMessage());
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

        return FileResponse.from(fileRepository.save(file));
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
    public void deleteFile(UUID id) {
        File file = findFileOrThrow(id);
        deleteFsEntry(Path.of(file.getFsPath()));
        deleteFromDbRecursively(file);
    }

    public DownloadPayload downloadSingle(UUID id) {
        File file = findFileOrThrow(id);
        if (!file.isDirectory()) {
            return buildSingleFilePayload(file);
        }

        return new DownloadPayload(file.getName() + ".zip", "application/zip", zipFiles(List.of(file)), -1);
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
        return new DownloadPayload(zipName, "application/zip", zipFiles(files), -1);
    }

    public ThumbnailPayload downloadThumbnail(UUID id, ThumbnailSize size) {
        File file = findFileOrThrow(id);
        if (file.isDirectory()) {
            throw new StorageException("Cannot generate thumbnail for a directory.");
        }

        Path path = Path.of(file.getFsPath());
        try {
            if (!Files.exists(path) || Files.isDirectory(path)) {
                throw new StorageException("File content is unavailable: " + file.getName());
            }

            String contentType = Files.probeContentType(path);
            if (contentType == null || contentType.isBlank()) {
                contentType = "application/octet-stream";
            }

            if (!contentType.startsWith("image/")) {
                throw new StorageException("Thumbnails are only supported for image files.");
            }

            byte[] originalContent = Files.readAllBytes(path);
            long lastModifiedEpochMs = Files.getLastModifiedTime(path).toMillis();
            ThumbnailBinary thumbnailBinary = tryBuildThumbnail(originalContent, contentType, size.maxDimensionPx());
            String eTag = "\"" + id + ":" + size.queryValue() + ":" + lastModifiedEpochMs + ":" + thumbnailBinary.content().length + "\"";

            return new ThumbnailPayload(thumbnailBinary.contentType(), thumbnailBinary.content(), lastModifiedEpochMs, eTag);
        } catch (IOException ex) {
            throw new StorageException("Failed to generate thumbnail for file: " + file.getName(), ex);
        }
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

    private Path chunkSessionsRoot() {
        Path root = Path.of(uploadRootPath).resolve("chunk_sessions").normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw new StorageException("Failed to create chunk sessions root: " + root, ex);
        }
        return root;
    }

    private Path chunkSessionDir(UUID uploadId) {
        return chunkSessionsRoot().resolve(uploadId.toString());
    }

    private ChunkUploadPersistedState loadChunkSessionOrThrow(UUID uploadId) {
        Path sessionDir = chunkSessionDir(uploadId);
        if (!Files.isDirectory(sessionDir)) {
            throw new ResourceNotFoundException("Chunk upload", "id", uploadId);
        }
        return readChunkSessionState(sessionDir);
    }

    private void assertChunkOwner(ChunkUploadPersistedState session, UUID authenticatedUserId) {
        if (!session.ownerId().equals(authenticatedUserId)) {
            throw new AccessDeniedException("Not owner of this upload session");
        }
    }

    private int resolvePartSizeBytes(Integer requested) {
        int base = requested != null ? requested : chunkDefaultPartSizeBytes;
        return Math.max(chunkMinPartSizeBytes, Math.min(chunkMaxPartSizeBytes, base));
    }

    private void writeChunkSessionState(Path sessionDir, ChunkUploadPersistedState state) {
        Path tmp = sessionDir.resolve("session.json.tmp");
        Path path = sessionDir.resolve("session.json");
        try {
            CHUNK_SESSION_JSON.writeValue(tmp.toFile(), state);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ex) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new StorageException("Failed to persist chunk session", ex);
        }
    }

    private ChunkUploadPersistedState readChunkSessionState(Path sessionDir) {
        Path path = sessionDir.resolve("session.json");
        return CHUNK_SESSION_JSON.readValue(path.toFile(), ChunkUploadPersistedState.class);
    }

    private void rollbackPartialChunk(Path dataPath, long confirmedBefore) {
        try {
            if (!Files.exists(dataPath)) {
                return;
            }
            try (FileChannel ch = FileChannel.open(dataPath, StandardOpenOption.WRITE)) {
                ch.truncate(confirmedBefore);
            }
        } catch (IOException ex) {
            log.warn("Failed to roll back partial chunk file {}: {}", dataPath, ex.getMessage());
        }
    }

    private long copyExactStream(InputStream in, OutputStream out, long exactBytes) throws IOException {
        byte[] buf = new byte[8192];
        long total = 0;
        while (total < exactBytes) {
            int toRead = (int) Math.min(buf.length, exactBytes - total);
            int r = in.read(buf, 0, toRead);
            if (r < 0) {
                return total;
            }
            out.write(buf, 0, r);
            total += r;
        }
        return total;
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

        return new DownloadPayload(file.getName(), contentType, body, contentLength);
    }

    private StreamingResponseBody zipFiles(List<File> files) {
        return outputStream -> {
            try (ZipOutputStream zipOut = new ZipOutputStream(outputStream)) {
                Set<String> usedRootNames = new LinkedHashSet<>();
                for (File file : files) {
                    Path source = Path.of(file.getFsPath());
                    if (!Files.exists(source)) {
                        throw new StorageException("Source path not found for download: " + source);
                    }

                    String rootName = uniqueRootName(file.getName(), usedRootNames);
                    if (Files.isDirectory(source)) {
                        zipDirectory(zipOut, source, rootName);
                    } else {
                        zipRegularFile(zipOut, source, rootName);
                    }
                }

                zipOut.finish();
            } catch (IOException ex) {
                throw new StorageException("Failed to build zip for download.", ex);
            }
        };
    }

    private void zipDirectory(ZipOutputStream zipOut, Path directoryPath, String rootName) throws IOException {
        try (var stream = Files.walk(directoryPath)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (path.equals(directoryPath)) {
                    continue;
                }

                Path relativePath = directoryPath.relativize(path);
                String entryName = rootName + "/" + relativePath.toString().replace('\\', '/');

                if (Files.isDirectory(path)) {
                    zipOut.putNextEntry(new ZipEntry(entryName + "/"));
                    zipOut.closeEntry();
                } else {
                    zipRegularFile(zipOut, path, entryName);
                }
            }
        }
    }

    private void zipRegularFile(ZipOutputStream zipOut, Path path, String entryName) throws IOException {
        zipOut.putNextEntry(new ZipEntry(entryName));
        Files.copy(path, zipOut);
        zipOut.closeEntry();
    }

    private String uniqueRootName(String baseName, Set<String> usedRootNames) {
        String candidate = baseName;
        int index = 1;
        while (!usedRootNames.add(candidate)) {
            candidate = baseName + "_" + index++;
        }
        return candidate;
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

    public record DownloadPayload(String fileName, String contentType, StreamingResponseBody body, long contentLength) {
    }

    public record ThumbnailPayload(
            String contentType,
            byte[] content,
            long lastModifiedEpochMs,
            String eTag
    ) {
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
