package com.r16a.r16a_cloud.user;

import com.r16a.r16a_cloud.exception.ResourceNotFoundException;
import com.r16a.r16a_cloud.file.FileService;
import com.r16a.r16a_cloud.file.support.ChunkedUploadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountDeletionServiceTests {

    @Mock
    private UserRepository userRepository;

    @Mock
    private FileService fileService;

    @Mock
    private ChunkedUploadService chunkedUploadService;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache thumbnails;

    private AccountDeletionService service() {
        return new AccountDeletionService(userRepository, fileService, chunkedUploadService, cacheManager);
    }

    @Test
    void erasesFilesUploadsAndCachesBeforeTheUser() {
        User user = User.builder().id(UUID.randomUUID()).build();
        UUID fileId = UUID.randomUUID();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(fileService.eraseAllOwnedBy(user.getId())).thenReturn(List.of(fileId));
        when(cacheManager.getCache("thumbnails")).thenReturn(thumbnails);

        service().deleteAccount(user.getId());

        InOrder order = inOrder(fileService, chunkedUploadService, userRepository);
        order.verify(fileService).eraseAllOwnedBy(user.getId());
        order.verify(chunkedUploadService).deleteSessionsOwnedBy(user.getId());
        order.verify(userRepository).delete(user);
        verify(thumbnails).evict(fileId + ":small");
        verify(thumbnails).evict(fileId + ":medium");
        verify(thumbnails).evict(fileId + ":large");
    }

    @Test
    void unknownUserIsNotFound() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service().deleteAccount(id));
        verifyNoInteractions(fileService, chunkedUploadService);
    }
}
