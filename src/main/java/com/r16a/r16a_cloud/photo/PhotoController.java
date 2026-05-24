package com.r16a.r16a_cloud.photo;

import com.r16a.r16a_cloud.file.dto.CursorPageResponse;
import com.r16a.r16a_cloud.file.dto.FileResponse;
import com.r16a.r16a_cloud.photo.dto.PhotoYearSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/photos")
@RequiredArgsConstructor
public class PhotoController {

    private final PhotoService photoService;

    @GetMapping("/years")
    public ResponseEntity<List<PhotoYearSummary>> getPhotoYears(@RequestParam UUID ownerId) {
        return ResponseEntity.ok(photoService.getPhotoYears(ownerId));
    }

    @GetMapping
    public ResponseEntity<CursorPageResponse<FileResponse>> getPhotos(
            @RequestParam UUID ownerId,
            @RequestParam int year,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "60") int limit
    ) {
        return ResponseEntity.ok(photoService.getPhotos(ownerId, year, cursor, limit));
    }
}
