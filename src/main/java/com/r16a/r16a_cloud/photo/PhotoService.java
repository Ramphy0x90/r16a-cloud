package com.r16a.r16a_cloud.photo;

import com.r16a.r16a_cloud.exception.ResourceNotFoundException;
import com.r16a.r16a_cloud.exception.StorageException;
import com.r16a.r16a_cloud.file.File;
import com.r16a.r16a_cloud.file.FileRepository;
import com.r16a.r16a_cloud.file.dto.CursorPageResponse;
import com.r16a.r16a_cloud.file.dto.FileResponse;
import com.r16a.r16a_cloud.photo.dto.PhotoYearSummary;
import com.r16a.r16a_cloud.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PhotoService {

    private static final JsonMapper CURSOR_JSON = JsonMapper.builder().build();

    private final FileRepository fileRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<PhotoYearSummary> getPhotoYears(UUID ownerId) {
        if (!userRepository.existsById(ownerId)) {
            throw new ResourceNotFoundException("User", "id", ownerId);
        }
        return fileRepository.findMediaYearCounts(ownerId).stream()
                .map(row -> new PhotoYearSummary(
                        ((Number) row[0]).intValue(),
                        ((Number) row[1]).longValue()))
                .toList();
    }

    @Transactional(readOnly = true)
    public CursorPageResponse<FileResponse> getPhotos(UUID ownerId, int year, String cursor, int limit) {
        if (!userRepository.existsById(ownerId)) {
            throw new ResourceNotFoundException("User", "id", ownerId);
        }

        int clampedLimit = Math.max(1, Math.min(200, limit));
        Instant yearStart = LocalDate.of(year, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant yearEnd = LocalDate.of(year + 1, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();

        Slice<File> slice;
        if (cursor == null || cursor.isBlank()) {
            slice = fileRepository.findMediaForYear(ownerId, yearStart, yearEnd, PageRequest.of(0, clampedLimit));
        } else {
            PhotoCursor pc = decodeCursor(cursor);
            slice = fileRepository.findMediaForYearCursor(
                    ownerId, yearStart, yearEnd,
                    Instant.parse(pc.lastCreatedAt()), UUID.fromString(pc.lastId()),
                    PageRequest.of(0, clampedLimit));
        }

        String nextCursor = null;
        if (slice.hasNext() && !slice.getContent().isEmpty()) {
            File last = slice.getContent().get(slice.getContent().size() - 1);
            nextCursor = encodeCursor(new PhotoCursor(last.getCreatedAt().toString(), last.getId().toString()));
        }

        return new CursorPageResponse<>(
                slice.getContent().stream().map(FileResponse::from).toList(),
                nextCursor,
                slice.hasNext());
    }

    private static String encodeCursor(PhotoCursor cursor) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(CURSOR_JSON.writeValueAsBytes(cursor));
        } catch (Exception ex) {
            throw new StorageException("Failed to encode photo cursor", ex);
        }
    }

    private static PhotoCursor decodeCursor(String encoded) {
        try {
            return CURSOR_JSON.readValue(Base64.getUrlDecoder().decode(encoded), PhotoCursor.class);
        } catch (Exception ex) {
            throw new StorageException("Invalid photo pagination cursor");
        }
    }

    private record PhotoCursor(String lastCreatedAt, String lastId) {}
}
