package com.r16a.r16a_cloud.file.support;

import com.r16a.r16a_cloud.file.FileEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileEventRetentionTaskTests {

    @Mock
    private FileEventRepository fileEventRepository;

    @Test
    void deletesEventsOlderThanNinetyDays() {
        FileEventRetentionTask task = new FileEventRetentionTask(fileEventRepository);
        ReflectionTestUtils.setField(task, "retentionDays", 90L);
        Instant now = Instant.parse("2026-10-03T12:00:00Z");
        when(fileEventRepository.deleteOccurredBefore(Instant.parse("2026-07-05T12:00:00Z")))
                .thenReturn(7);

        assertEquals(7, task.purgeOlderThanRetention(now));
    }
}
