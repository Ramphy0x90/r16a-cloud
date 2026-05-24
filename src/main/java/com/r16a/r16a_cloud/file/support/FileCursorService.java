package com.r16a.r16a_cloud.file.support;

import com.r16a.r16a_cloud.exception.ResourceNotFoundException;
import com.r16a.r16a_cloud.exception.StorageException;
import com.r16a.r16a_cloud.file.File;
import com.r16a.r16a_cloud.file.FileRepository;
import com.r16a.r16a_cloud.file.dto.CursorPageResponse;
import com.r16a.r16a_cloud.file.dto.FileResponse;
import com.r16a.r16a_cloud.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileCursorService {

    private static final JsonMapper CURSOR_JSON = JsonMapper.builder().build();

    private final FileRepository fileRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public CursorPageResponse<FileResponse> getFilesCursorPage(
            UUID ownerId, UUID parentId, String sortField, String sortDir, String cursor, int limit) {
        if (!userRepository.existsById(ownerId)) {
            throw new ResourceNotFoundException("User", "id", ownerId);
        }

        Pageable p = PageRequest.of(0, limit);
        Slice<File> slice;

        if (cursor == null || cursor.isBlank()) {
            Sort idTiebreak = Sort.by(Sort.Direction.ASC, "id");
            Sort fieldSort = "updatedAt".equals(sortField)
                    ? Sort.by(Sort.Direction.fromString(sortDir), "updatedAt").and(idTiebreak)
                    : Sort.by(Sort.Direction.fromString(sortDir), "name").and(idTiebreak);
            Sort fullSort = Sort.by(Sort.Direction.DESC, "isDirectory").and(fieldSort);

            Pageable firstPage = PageRequest.of(0, limit, fullSort);
            slice = parentId != null
                    ? fileRepository.findSliceByParentIdAndOwnerId(parentId, ownerId, firstPage)
                    : fileRepository.findSliceByParentIsNullAndOwnerId(ownerId, firstPage);
        } else {
            FileCursor fc = decodeCursor(cursor);
            slice = fetchByCursor(ownerId, parentId, fc, p);
            sortField = fc.sortField();
            sortDir = fc.sortDir();
        }

        String nextCursor = null;
        if (slice.hasNext() && !slice.getContent().isEmpty()) {
            File last = slice.getContent().get(slice.getContent().size() - 1);
            String lastSortValue = "updatedAt".equals(sortField)
                    ? last.getUpdatedAt().toString()
                    : last.getName();
            nextCursor = encodeCursor(new FileCursor(sortField, sortDir, last.isDirectory(), lastSortValue, last.getId().toString()));
        }

        return new CursorPageResponse<>(
                slice.getContent().stream().map(FileResponse::from).toList(),
                nextCursor,
                slice.hasNext()
        );
    }

    private Slice<File> fetchByCursor(UUID ownerId, UUID parentId, FileCursor fc, Pageable p) {
        boolean isDir = fc.lastIsDir();
        UUID lastId = UUID.fromString(fc.lastId());

        return switch (fc.sortField() + ":" + fc.sortDir()) {
            case "name:asc" -> parentId != null
                    ? fileRepository.findCursorNameAscByParentId(ownerId, parentId, isDir, fc.lastSortValue(), lastId, p)
                    : fileRepository.findCursorNameAscRoot(ownerId, isDir, fc.lastSortValue(), lastId, p);
            case "name:desc" -> parentId != null
                    ? fileRepository.findCursorNameDescByParentId(ownerId, parentId, isDir, fc.lastSortValue(), lastId, p)
                    : fileRepository.findCursorNameDescRoot(ownerId, isDir, fc.lastSortValue(), lastId, p);
            case "updatedAt:asc" -> {
                Instant t = Instant.parse(fc.lastSortValue());
                yield parentId != null
                        ? fileRepository.findCursorUpdatedAtAscByParentId(ownerId, parentId, isDir, t, lastId, p)
                        : fileRepository.findCursorUpdatedAtAscRoot(ownerId, isDir, t, lastId, p);
            }
            case "updatedAt:desc" -> {
                Instant t = Instant.parse(fc.lastSortValue());
                yield parentId != null
                        ? fileRepository.findCursorUpdatedAtDescByParentId(ownerId, parentId, isDir, t, lastId, p)
                        : fileRepository.findCursorUpdatedAtDescRoot(ownerId, isDir, t, lastId, p);
            }
            default ->
                    throw new StorageException("Unsupported sort combination: " + fc.sortField() + " " + fc.sortDir());
        };
    }

    private static String encodeCursor(FileCursor cursor) {
        try {
            byte[] json = CURSOR_JSON.writeValueAsBytes(cursor);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (Exception ex) {
            throw new StorageException("Failed to encode cursor", ex);
        }
    }

    private static FileCursor decodeCursor(String encoded) {
        try {
            byte[] json = Base64.getUrlDecoder().decode(encoded);
            return CURSOR_JSON.readValue(json, FileCursor.class);
        } catch (Exception ex) {
            throw new StorageException("Invalid pagination cursor");
        }
    }

    private record FileCursor(String sortField, String sortDir, boolean lastIsDir, String lastSortValue,
                              String lastId) {
    }
}
