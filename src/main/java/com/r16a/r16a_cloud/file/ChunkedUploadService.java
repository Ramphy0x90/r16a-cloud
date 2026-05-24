package com.r16a.r16a_cloud.file;

import com.r16a.r16a_cloud.exception.ResourceAlreadyExistsException;
import com.r16a.r16a_cloud.exception.ResourceNotFoundException;
import com.r16a.r16a_cloud.exception.StorageException;
import com.r16a.r16a_cloud.file.dto.*;
import com.r16a.r16a_cloud.user.User;
import com.r16a.r16a_cloud.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkedUploadService {

    private static final JsonMapper SESSION_JSON = JsonMapper.builder().build();

    private final FileRepository fileRepository;
    private final UserRepository userRepository;
    private final FileEventRepository fileEventRepository;
    private final ThumbnailService thumbnailService;

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

    public ChunkUploadInitResponse initChunkedUpload(ChunkUploadInitRequest request, UUID authenticatedUserId) {
        if (!request.ownerId().equals(authenticatedUserId)) {
            throw new AccessDeniedException("ownerId must match authenticated user");
        }

        if (request.totalSize() < 0) {
            throw new StorageException("totalSize must be non-negative.");
        }

        String fileName = sanitizeFilenameFromString(request.fileName());
        File parent = resolveParentForOwner(request.parentId(), request.ownerId());
        checkDuplicateNameForCreate(fileName, parent, request.ownerId());

        int partSizeBytes = resolvePartSizeBytes(request.partSizeBytes());
        if (request.totalSize() > 0 && partSizeBytes <= 0) {
            throw new StorageException("partSizeBytes must be positive when totalSize is positive.");
        }

        UUID uploadId = UUID.randomUUID();
        Path sessionDir = sessionDir(uploadId);
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
        writeSessionState(sessionDir, state);
        return new ChunkUploadInitResponse(uploadId, partSizeBytes);
    }

    public void uploadChunk(UUID uploadId, UUID authenticatedUserId, InputStream body) {
        ChunkUploadPersistedState session = loadSessionOrThrow(uploadId);
        assertOwner(session, authenticatedUserId);

        if (session.totalSize() == 0) {
            throw new StorageException("Cannot upload parts for a zero-byte file; call complete instead.");
        }

        long remaining = session.totalSize() - session.receivedBytes();
        if (remaining <= 0) {
            throw new StorageException("Upload already complete on disk; call complete to finalize.");
        }

        long expectedThisPart = Math.min(session.partSizeBytes(), remaining);
        Path sessionDir = sessionDir(uploadId);
        Path dataPath = sessionDir.resolve("data.bin");
        long confirmedBefore = session.receivedBytes();
        long copied;

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
        writeSessionState(sessionDir, session.withReceivedBytes(newReceived));
    }

    public ChunkUploadStatusResponse getChunkedUploadStatus(UUID uploadId, UUID authenticatedUserId) {
        ChunkUploadPersistedState session = loadSessionOrThrow(uploadId);
        assertOwner(session, authenticatedUserId);
        return new ChunkUploadStatusResponse(session.receivedBytes(), session.totalSize(), session.partSizeBytes());
    }

    @Transactional
    public FileResponse completeChunkedUpload(UUID uploadId, UUID authenticatedUserId) {
        ChunkUploadPersistedState session = loadSessionOrThrow(uploadId);
        assertOwner(session, authenticatedUserId);

        if (session.receivedBytes() != session.totalSize()) {
            throw new StorageException(
                    "Incomplete upload: received " + session.receivedBytes() + " of " + session.totalSize() + " bytes."
            );
        }

        User owner = userRepository.findById(session.ownerId())
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", session.ownerId()));

        File parent = resolveParentForOwner(session.parentId(), session.ownerId());
        checkDuplicateNameForCreate(session.fileName(), parent, session.ownerId());

        Path sessionDir = sessionDir(uploadId);
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

        String blurHash = thumbnailService.computeBlurHash(targetPath);

        Set<UUID> sharedWithIds = session.sharedWithIds() != null ? session.sharedWithIds() : Set.of();
        File file = File.builder()
                .name(session.fileName())
                .description(session.description())
                .fsPath(targetPath.toString())
                .isDirectory(false)
                .sizeBytes(session.totalSize())
                .visibility(session.visibility() != null ? session.visibility() : Visibility.PRIVATE)
                .parent(parent)
                .owner(owner)
                .sharedWith(resolveUsers(sharedWithIds))
                .blurHash(blurHash)
                .build();

        try {
            FileResponse response = FileResponse.from(fileRepository.save(file));
            recordEvent(file, FileEventType.CREATED);
            deleteFsEntry(sessionDir);
            return response;
        } catch (RuntimeException ex) {
            rollbackFsEntry(targetPath);
            throw ex;
        }
    }

    public void cancelChunkedUpload(UUID uploadId, UUID authenticatedUserId) {
        ChunkUploadPersistedState session = loadSessionOrThrow(uploadId);
        assertOwner(session, authenticatedUserId);
        deleteFsEntry(sessionDir(uploadId));
    }

    public void deleteExpiredChunkUploadSessions() {
        Path root = sessionsRoot();
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
                    ChunkUploadPersistedState s = readSessionState(dir);
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

    private ChunkUploadPersistedState loadSessionOrThrow(UUID uploadId) {
        Path dir = sessionDir(uploadId);
        if (!Files.isDirectory(dir)) {
            throw new ResourceNotFoundException("Chunk upload", "id", uploadId);
        }
        return readSessionState(dir);
    }

    private void assertOwner(ChunkUploadPersistedState session, UUID authenticatedUserId) {
        if (!session.ownerId().equals(authenticatedUserId)) {
            throw new AccessDeniedException("Not owner of this upload session");
        }
    }

    private Path sessionsRoot() {
        Path root = Path.of(uploadRootPath).resolve("chunk_sessions").normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw new StorageException("Failed to create chunk sessions root: " + root, ex);
        }
        return root;
    }

    private Path sessionDir(UUID uploadId) {
        return sessionsRoot().resolve(uploadId.toString());
    }

    private int resolvePartSizeBytes(Integer requested) {
        int base = requested != null ? requested : chunkDefaultPartSizeBytes;
        return Math.max(chunkMinPartSizeBytes, Math.min(chunkMaxPartSizeBytes, base));
    }

    private void writeSessionState(Path sessionDir, ChunkUploadPersistedState state) {
        Path tmp = sessionDir.resolve("session.json.tmp");
        Path path = sessionDir.resolve("session.json");
        try {
            SESSION_JSON.writeValue(tmp.toFile(), state);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ex) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new StorageException("Failed to persist chunk session", ex);
        }
    }

    private ChunkUploadPersistedState readSessionState(Path sessionDir) {
        Path path = sessionDir.resolve("session.json");
        return SESSION_JSON.readValue(path.toFile(), ChunkUploadPersistedState.class);
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

    private File resolveParentForOwner(UUID parentId, UUID ownerId) {
        if (parentId == null) {
            return null;
        }

        File parent = fileRepository.findById(parentId)
                .orElseThrow(() -> new ResourceNotFoundException("File", "id", parentId));

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
                    "Failed to create owner root '" + ownerRoot + "': " + ex.getMessage(), ex
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
                    "Failed to create filesystem entry: " + targetPath + " (" + ex.getMessage() + ")", ex
            );
        }
    }

    private void deleteFsEntry(Path targetPath) {
        if (!Files.exists(targetPath)) {
            return;
        }

        try (Stream<Path> stream = Files.walk(targetPath)) {
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
}
