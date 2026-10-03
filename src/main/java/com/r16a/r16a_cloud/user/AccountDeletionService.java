package com.r16a.r16a_cloud.user;

import com.r16a.r16a_cloud.exception.ResourceNotFoundException;
import com.r16a.r16a_cloud.file.FileService;
import com.r16a.r16a_cloud.file.support.ChunkedUploadService;
import com.r16a.r16a_cloud.file.support.ThumbnailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Erases an account and everything it owns (GDPR Art. 17 / app-store account deletion): files
 * and their storage, thumbnails, the file event log, share entries on other users' files,
 * in-progress uploads, cached data, and the user record with its preferences.
 *
 * <p>The identity at the IdP (Authentik) is not touched: with invite-only sign-up an admin
 * deactivates it there. Until then, signing in again would provision a fresh, empty account.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountDeletionService {

    private final UserRepository userRepository;
    private final FileService fileService;
    private final ChunkedUploadService chunkedUploadService;
    private final CacheManager cacheManager;

    @Transactional
    public void deleteAccount(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        List<UUID> fileIds = fileService.eraseAllOwnedBy(userId);
        evictThumbnails(fileIds);
        chunkedUploadService.deleteSessionsOwnedBy(userId);
        userRepository.delete(user);

        log.info("Deleted account {} ({} files)", userId, fileIds.size());
    }

    private void evictThumbnails(List<UUID> fileIds) {
        Cache thumbnails = cacheManager.getCache("thumbnails");
        if (thumbnails == null) return;
        for (UUID id : fileIds) {
            for (ThumbnailService.ThumbnailSize size : ThumbnailService.ThumbnailSize.values()) {
                thumbnails.evict(id + ":" + size.queryValue());
            }
        }
    }
}
