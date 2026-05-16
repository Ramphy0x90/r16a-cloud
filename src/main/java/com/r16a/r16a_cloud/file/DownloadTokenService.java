package com.r16a.r16a_cloud.file;

import com.r16a.r16a_cloud.exception.StorageException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
public class DownloadTokenService {

    private static final int TOKEN_TTL_SECONDS = 300;

    private final byte[] secret;

    public DownloadTokenService(
            @Value("${app.download.token-secret:r16a-default-change-in-production}") String secret
    ) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** Returns a base64url-encoded signed token valid for {@value TOKEN_TTL_SECONDS} seconds. */
    public String generateToken(UUID fileId, UUID requesterId) {
        long expiresAt = Instant.now().getEpochSecond() + TOKEN_TTL_SECONDS;
        String payload = fileId + ":" + requesterId + ":" + expiresAt;
        String sig = sign(payload);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((payload + ":" + sig).getBytes(StandardCharsets.UTF_8));
    }

    /** Validates the token and returns the {@code fileId} it was issued for. */
    public UUID validateToken(String token) {
        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new StorageException("Malformed download token");
        }

        // payload = fileId:requesterId:expiresAt   sig = last segment
        int lastColon = decoded.lastIndexOf(':');
        if (lastColon < 0) throw new StorageException("Malformed download token");

        String payload = decoded.substring(0, lastColon);
        String providedSig = decoded.substring(lastColon + 1);
        String expectedSig = sign(payload);

        if (!MessageDigest.isEqual(expectedSig.getBytes(StandardCharsets.UTF_8),
                                   providedSig.getBytes(StandardCharsets.UTF_8))) {
            throw new StorageException("Invalid download token");
        }

        String[] parts = payload.split(":", 3);
        if (parts.length != 3) throw new StorageException("Malformed download token");

        long expiresAt = Long.parseLong(parts[2]);
        if (Instant.now().getEpochSecond() > expiresAt) throw new StorageException("Download token expired");

        return UUID.fromString(parts[0]);
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new StorageException("Token signing failed", ex);
        }
    }
}
