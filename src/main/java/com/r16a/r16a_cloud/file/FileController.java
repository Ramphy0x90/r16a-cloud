package com.r16a.r16a_cloud.file;

import com.r16a.r16a_cloud.file.dto.CreateFileRequest;
import com.r16a.r16a_cloud.file.dto.FileResponse;
import com.r16a.r16a_cloud.file.dto.UpdateFileRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    @GetMapping("/{id}")
    public ResponseEntity<FileResponse> getFile(@PathVariable Long id) {
        return ResponseEntity.ok(fileService.getFileById(id));
    }

    @GetMapping
    public ResponseEntity<Page<FileResponse>> listFiles(
            @RequestParam Long ownerId,
            @RequestParam(required = false) Long parentId,
            Pageable pageable) {
        return ResponseEntity.ok(fileService.listFiles(ownerId, parentId, pageable));
    }

    @PutMapping("/{id}")
    public ResponseEntity<FileResponse> updateFile(
            @PathVariable Long id,
            @Valid @RequestBody UpdateFileRequest request) {
        return ResponseEntity.ok(fileService.updateFile(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFile(@PathVariable Long id) {
        fileService.deleteFile(id);
        return ResponseEntity.noContent().build();
    }
}
