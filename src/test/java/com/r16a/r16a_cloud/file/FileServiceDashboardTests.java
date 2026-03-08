package com.r16a.r16a_cloud.file;

import com.r16a.r16a_cloud.exception.ResourceNotFoundException;
import com.r16a.r16a_cloud.file.dto.DashboardResponse;
import com.r16a.r16a_cloud.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileServiceDashboardTests {

    @Mock
    private FileRepository fileRepository;

    @Mock
    private UserRepository userRepository;

    @Test
    void getDashboard_returnsAggregatesAndRecentFiles() {
        UUID ownerId = UUID.randomUUID();
        when(userRepository.existsById(ownerId)).thenReturn(true);
        when(fileRepository.countByOwnerIdAndIsDirectoryFalse(ownerId)).thenReturn(12L);
        when(fileRepository.sumFileSizeBytesByOwnerId(ownerId)).thenReturn(2048L);
        when(fileRepository.countSharedFilesByOwnerId(ownerId)).thenReturn(3L);

        File recentA = File.builder()
                .id(UUID.randomUUID())
                .name("report.pdf")
                .visibility(Visibility.SHARED)
                .sizeBytes(1024L)
                .updatedAt(Instant.parse("2026-03-08T10:15:30Z"))
                .build();
        File recentB = File.builder()
                .id(UUID.randomUUID())
                .name("image.png")
                .visibility(Visibility.PRIVATE)
                .sizeBytes(512L)
                .updatedAt(Instant.parse("2026-03-07T10:15:30Z"))
                .build();

        when(fileRepository.findTop5ByOwnerIdAndIsDirectoryFalseOrderByUpdatedAtDesc(ownerId))
                .thenReturn(List.of(recentA, recentB));

        FileService service = new FileService(fileRepository, userRepository);
        DashboardResponse response = service.getDashboard(ownerId);

        assertEquals(12L, response.metrics().uploadedFiles());
        assertEquals(2048L, response.metrics().usedStorageBytes());
        assertEquals(3L, response.metrics().sharedFiles());
        assertEquals(2, response.recentFiles().size());
        assertEquals("report.pdf", response.recentFiles().getFirst().name());
        assertEquals(Visibility.SHARED, response.recentFiles().getFirst().visibility());
        assertEquals(1024L, response.recentFiles().getFirst().sizeBytes());
    }

    @Test
    void getDashboard_throwsWhenOwnerDoesNotExist() {
        UUID ownerId = UUID.randomUUID();
        when(userRepository.existsById(ownerId)).thenReturn(false);

        FileService service = new FileService(fileRepository, userRepository);

        assertThrows(ResourceNotFoundException.class, () -> service.getDashboard(ownerId));
        verifyNoInteractions(fileRepository);
    }
}
