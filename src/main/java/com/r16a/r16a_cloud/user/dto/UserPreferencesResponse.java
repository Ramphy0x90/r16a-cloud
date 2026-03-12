package com.r16a.r16a_cloud.user.dto;

import com.r16a.r16a_cloud.user.UserPreferences;

public record UserPreferencesResponse(
        String preferredTheme,
        boolean encryptFilesByDefault
) {
    public static UserPreferencesResponse from(UserPreferences preferences) {
        UserPreferences safePreferences = preferences != null ? preferences : UserPreferences.builder().build();
        return new UserPreferencesResponse(
                safePreferences.getPreferredTheme(),
                safePreferences.isEncryptFilesByDefault()
        );
    }
}
