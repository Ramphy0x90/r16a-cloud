package com.r16a.r16a_cloud.file;

import com.r16a.r16a_cloud.exception.StorageException;
import com.r16a.r16a_cloud.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileServiceThumbnailTests {

    @Mock
    private FileRepository fileRepository;

    @Mock
    private UserRepository userRepository;

    @TempDir
    Path tempDir;

    @Test
    void downloadThumbnail_resizesLargeImage() throws Exception {
        UUID fileId = UUID.randomUUID();
        Path imagePath = tempDir.resolve("large.jpg");
        BufferedImage image = new BufferedImage(1600, 900, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, "jpg", imagePath.toFile());

        File file = File.builder()
                .id(fileId)
                .name("large.jpg")
                .fsPath(imagePath.toString())
                .isDirectory(false)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(fileRepository.findById(fileId)).thenReturn(Optional.of(file));

        FileService service = new FileService(fileRepository, userRepository);
        FileService.ThumbnailPayload payload = service.downloadThumbnail(fileId, FileService.ThumbnailSize.SMALL);

        assertNotNull(payload);
        assertTrue(payload.contentType().startsWith("image/"));
        assertNotNull(payload.eTag());
        assertTrue(payload.lastModifiedEpochMs() > 0);

        BufferedImage resized = ImageIO.read(new java.io.ByteArrayInputStream(payload.content()));
        assertNotNull(resized);
        assertTrue(Math.max(resized.getWidth(), resized.getHeight()) <= 200);
    }

    @Test
    void downloadThumbnail_throwsForNonImageFile() throws Exception {
        UUID fileId = UUID.randomUUID();
        Path textPath = tempDir.resolve("notes.txt");
        Files.writeString(textPath, "hello");

        File file = File.builder()
                .id(fileId)
                .name("notes.txt")
                .fsPath(textPath.toString())
                .isDirectory(false)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(fileRepository.findById(fileId)).thenReturn(Optional.of(file));

        FileService service = new FileService(fileRepository, userRepository);
        assertThrows(StorageException.class, () ->
                service.downloadThumbnail(fileId, FileService.ThumbnailSize.SMALL)
        );
    }
}
