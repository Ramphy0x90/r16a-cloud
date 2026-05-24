package com.r16a.r16a_cloud.photo;

import com.r16a.r16a_cloud.file.FileRepository;
import com.r16a.r16a_cloud.file.support.MediaDateExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class MediaMetadataBackfillService {

    private final FileRepository fileRepository;
    private final MediaDateExtractor mediaDateExtractor;

    @EventListener(ApplicationReadyEvent.class)
    public void scheduleBackfill() {
        Thread.ofVirtual().name("takenAt-backfill").start(this::backfillTakenAt);
    }

    private void backfillTakenAt() {
        List<UUID> ids = fileRepository.findAllImageIdsMissingTakenAt();
        if (ids.isEmpty()) return;

        log.info("Backfilling takenAt for {} image(s) missing EXIF date...", ids.size());
        int updated = 0;

        for (UUID id : ids) {
            try {
                fileRepository.findById(id).ifPresent(file -> {
                    Instant takenAt = mediaDateExtractor.extractOldestDate(Path.of(file.getFsPath()));
                    if (takenAt != null) {
                        fileRepository.updateTakenAt(file.getId(), takenAt);
                    }
                });
                updated++;
            } catch (Exception ex) {
                log.debug("Could not backfill takenAt for file {}: {}", id, ex.getMessage());
            }
        }

        log.info("takenAt backfill complete — updated {}/{} files.", updated, ids.size());
    }
}
