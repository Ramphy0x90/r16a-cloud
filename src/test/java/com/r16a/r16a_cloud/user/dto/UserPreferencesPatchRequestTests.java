package com.r16a.r16a_cloud.user.dto;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserPreferencesPatchRequestTests {

    @Test
    void oldClientsSendingTheRemovedEncryptionFlagStillDeserialize() {
        // Strict mapper: the record's own annotation must make this pass.
        JsonMapper mapper = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();

        UserPreferencesPatchRequest request = mapper.readValue(
                "{\"preferredTheme\":\"dark\",\"encryptFilesByDefault\":true,\"defaultViewMode\":\"list\"}",
                UserPreferencesPatchRequest.class
        );

        assertEquals("dark", request.preferredTheme());
        assertEquals("list", request.defaultViewMode());
    }
}
