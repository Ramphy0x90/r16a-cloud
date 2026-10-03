package com.r16a.r16a_cloud.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileServiceFolderETagTests {

    @Mock
    private FileRepository fileRepository;

    private final UUID ownerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID folderId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final Instant newest = Instant.parse("2026-10-03T12:00:00Z");

    private FileService service() {
        return new FileService(fileRepository, null, null, null, null, null, null, null, null);
    }

    @Test
    void removingAChildChangesTheETagEvenWhenTheNewestIsUnchanged() {
        when(fileRepository.findFolderVersion(ownerId, folderId))
                .thenReturn(new FolderVersion(3L, newest))
                .thenReturn(new FolderVersion(2L, newest));

        String before = service().getFolderETag(ownerId, folderId);
        String after = service().getFolderETag(ownerId, folderId);

        assertNotEquals(before, after);
        assertEquals("\"" + ownerId + ":" + folderId + ":3:" + newest.toEpochMilli() + "\"", before);
    }

    @Test
    void emptyRootFolderUsesEpoch() {
        when(fileRepository.findRootFolderVersion(ownerId)).thenReturn(new FolderVersion(0L, null));

        assertEquals("\"" + ownerId + ":root:0:0\"", service().getFolderETag(ownerId, null));
    }
}
