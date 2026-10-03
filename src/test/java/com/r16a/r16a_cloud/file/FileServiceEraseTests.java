package com.r16a.r16a_cloud.file;

import com.r16a.r16a_cloud.file.support.ThumbnailService;
import com.r16a.r16a_cloud.photo.PhotoService;
import com.r16a.r16a_cloud.user.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileServiceEraseTests {

    @Mock
    private FileRepository fileRepository;

    @Mock
    private FileEventRepository fileEventRepository;

    @Mock
    private ThumbnailService thumbnailService;

    @Mock
    private PhotoService photoService;

    @TempDir
    Path uploadRoot;

    private final User leaving = User.builder().id(UUID.randomUUID()).build();
    private final User friend = User.builder().id(UUID.randomUUID()).build();

    private FileService service() {
        FileService service = new FileService(
                fileRepository, null, fileEventRepository, null, thumbnailService, null, null, photoService, null);
        ReflectionTestUtils.setField(service, "uploadRootPath", uploadRoot.toString());
        return service;
    }

    private File file(User owner, String path, Set<User> sharedWith, Visibility visibility) {
        return File.builder()
                .id(UUID.randomUUID())
                .name(Path.of(path).getFileName().toString())
                .fsPath(uploadRoot.resolve(path).toString())
                .owner(owner)
                .sharedWith(new HashSet<>(sharedWith))
                .visibility(visibility)
                .build();
    }

    @Test
    void erasesOwnedFilesChildrenFirstAndLeavesOthersShares() throws Exception {
        String root = "user_" + leaving.getId();
        File folder = file(leaving, root + "/docs", Set.of(), Visibility.PRIVATE);
        File child = file(leaving, root + "/docs/a.txt", Set.of(), Visibility.PRIVATE);
        File friendsFile = file(friend, "user_" + friend.getId() + "/x.jpg", Set.of(leaving), Visibility.SHARED);
        when(fileRepository.findAllSharedWithUser(leaving.getId())).thenReturn(List.of(friendsFile));
        when(fileRepository.findByOwnerId(leaving.getId())).thenReturn(List.of(folder, child));
        Files.createDirectories(uploadRoot.resolve(root).resolve("docs"));
        Files.writeString(uploadRoot.resolve(root).resolve("docs/a.txt"), "secret");

        List<UUID> erased = service().eraseAllOwnedBy(leaving.getId());

        // The friend's file no longer lists them and falls back to private.
        assertTrue(friendsFile.getSharedWith().isEmpty());
        assertEquals(Visibility.PRIVATE, friendsFile.getVisibility());
        verify(fileRepository).save(friendsFile);

        InOrder order = inOrder(fileRepository);
        order.verify(fileRepository).delete(child);
        order.verify(fileRepository).delete(folder);
        verify(fileEventRepository).deleteByOwnerId(leaving.getId());
        verify(photoService).evictYearsCache(leaving.getId());

        assertEquals(Set.of(folder.getId(), child.getId()), Set.copyOf(erased));
        verify(thumbnailService).deleteThumbnailCache(folder.getId());
        verify(thumbnailService).deleteThumbnailCache(child.getId());
        // No transaction in this test, so storage is removed right away.
        assertFalse(Files.exists(uploadRoot.resolve(root)));
    }
}
