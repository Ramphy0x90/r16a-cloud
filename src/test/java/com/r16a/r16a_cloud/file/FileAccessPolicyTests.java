package com.r16a.r16a_cloud.file;

import com.r16a.r16a_cloud.exception.ResourceNotFoundException;
import com.r16a.r16a_cloud.file.support.FileAccessPolicy;
import com.r16a.r16a_cloud.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileAccessPolicyTests {

    @Mock
    private FileRepository fileRepository;

    private FileAccessPolicy policy;

    private final User owner = User.builder().id(UUID.randomUUID()).build();
    private final User friend = User.builder().id(UUID.randomUUID()).build();
    private final User stranger = User.builder().id(UUID.randomUUID()).build();

    @BeforeEach
    void setUp() {
        policy = new FileAccessPolicy(fileRepository);
    }

    private File file(File parent, User... sharedWith) {
        return File.builder()
                .id(UUID.randomUUID())
                .name("f")
                .owner(owner)
                .parent(parent)
                .sharedWith(new HashSet<>(Set.of(sharedWith)))
                .build();
    }

    private File stored(File file) {
        when(fileRepository.findById(file.getId())).thenReturn(Optional.of(file));
        return file;
    }

    @Test
    void ownerCanReadAndWrite() {
        File file = stored(file(null));

        assertSame(file, policy.requireReadable(file.getId(), owner.getId()));
        assertSame(file, policy.requireOwned(file.getId(), owner.getId()));
    }

    @Test
    void directShareGrantsReadButNotWrite() {
        File file = stored(file(null, friend));

        assertSame(file, policy.requireReadable(file.getId(), friend.getId()));
        assertThrows(AccessDeniedException.class, () -> policy.requireOwned(file.getId(), friend.getId()));
    }

    @Test
    void sharedAncestorGrantsReadToDescendants() {
        File sharedFolder = file(null, friend);
        File child = stored(file(file(sharedFolder)));

        assertSame(child, policy.requireReadable(child.getId(), friend.getId()));
    }

    @Test
    void strangerSeesNotFoundForReadAndWrite() {
        File file = stored(file(file(null, friend), friend));

        assertThrows(ResourceNotFoundException.class, () -> policy.requireReadable(file.getId(), stranger.getId()));
        assertThrows(ResourceNotFoundException.class, () -> policy.requireOwned(file.getId(), stranger.getId()));
    }

    @Test
    void missingFileIsNotFound() {
        UUID id = UUID.randomUUID();
        when(fileRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> policy.requireReadable(id, owner.getId()));
    }

    @Test
    void requireSelfOnlyAcceptsTheCaller() {
        assertDoesNotThrow(() -> FileAccessPolicy.requireSelf(owner.getId(), owner.getId()));
        assertThrows(ResourceNotFoundException.class,
                () -> FileAccessPolicy.requireSelf(owner.getId(), stranger.getId()));
        assertThrows(ResourceNotFoundException.class,
                () -> FileAccessPolicy.requireSelf(null, owner.getId()));
    }
}
