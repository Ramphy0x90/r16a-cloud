package com.r16a.r16a_cloud.file.support;

import com.r16a.r16a_cloud.exception.ResourceNotFoundException;
import com.r16a.r16a_cloud.file.File;
import com.r16a.r16a_cloud.file.FileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Single place that decides who may touch a file.
 * <ul>
 *   <li>Read: the owner, a user the file is shared with, or a user any ancestor folder is shared with.</li>
 *   <li>Write (rename, move, share, delete): the owner only.</li>
 * </ul>
 * Files the requester cannot read are reported as not found, so ids of other users' files are not
 * confirmed to exist. A reader who is not the owner gets 403 on writes.
 */
@Component
@RequiredArgsConstructor
public class FileAccessPolicy {

    private final FileRepository fileRepository;

    /**
     * Owner-scoped endpoints take an {@code ownerId} parameter; it must be the caller. A mismatch
     * is reported as an unknown user rather than 403, so user ids cannot be probed.
     */
    public static void requireSelf(UUID ownerId, UUID requesterId) {
        if (ownerId == null || !ownerId.equals(requesterId)) {
            throw new ResourceNotFoundException("User", "id", ownerId);
        }
    }

    public File requireReadable(UUID fileId, UUID requesterId) {
        File file = fileRepository.findById(fileId)
                .orElseThrow(() -> new ResourceNotFoundException("File", "id", fileId));
        if (!canRead(file, requesterId)) {
            throw new ResourceNotFoundException("File", "id", fileId);
        }
        return file;
    }

    public File requireOwned(UUID fileId, UUID requesterId) {
        File file = requireReadable(fileId, requesterId);
        if (!isOwner(file, requesterId)) {
            throw new AccessDeniedException("Only the owner can modify this file");
        }
        return file;
    }

    public boolean canRead(File file, UUID requesterId) {
        if (isOwner(file, requesterId)) {
            return true;
        }
        for (File node = file; node != null; node = node.getParent()) {
            boolean sharedWithRequester = node.getSharedWith().stream()
                    .anyMatch(user -> user.getId().equals(requesterId));
            if (sharedWithRequester) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOwner(File file, UUID requesterId) {
        return file.getOwner().getId().equals(requesterId);
    }
}
