package com.r16a.r16a_cloud.file.support;

import com.r16a.r16a_cloud.file.FileEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Deletes file events (the delta-sync log, which includes file names) older than the retention
 * period promised in the privacy policy (90 days by default). Clients only ever ask for recent
 * events, so nothing that still matters is lost.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileEventRetentionTask {

    private final FileEventRepository fileEventRepository;

    @Value("${app.events.retention-days:90}")
    private long retentionDays;

    /**
     * Purge every 6hours (21600000ms)
     */
    @Scheduled(fixedRateString = "${app.events.retention-interval-ms:21600000}")
    public void purgeExpiredEvents() {
        purgeOlderThanRetention(Instant.now());
    }

    /**
     * Deletes events older than the retention period as of [now]; returns how many.
     */
    int purgeOlderThanRetention(Instant now) {
        int deleted = fileEventRepository.deleteOccurredBefore(now.minus(Duration.ofDays(retentionDays)));
        if (deleted > 0) log.info("Purged {} file events older than {} days", deleted, retentionDays);
        return deleted;
    }
}
