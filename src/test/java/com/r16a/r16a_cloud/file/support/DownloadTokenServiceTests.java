package com.r16a.r16a_cloud.file.support;

import com.r16a.r16a_cloud.exception.StorageException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DownloadTokenServiceTests {

    @Test
    void tokenCarriesFileAndRequester() {
        DownloadTokenService service = new DownloadTokenService("test-secret");
        UUID fileId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();

        DownloadTokenService.TokenClaims claims = service.validateToken(service.generateToken(fileId, requesterId));

        assertEquals(fileId, claims.fileId());
        assertEquals(requesterId, claims.requesterId());
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        String forged = new DownloadTokenService("other-secret").generateToken(UUID.randomUUID(), UUID.randomUUID());

        assertThrows(StorageException.class, () -> new DownloadTokenService("test-secret").validateToken(forged));
    }
}
