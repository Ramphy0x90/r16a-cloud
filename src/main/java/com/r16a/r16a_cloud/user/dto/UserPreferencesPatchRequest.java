package com.r16a.r16a_cloud.user.dto;

import jakarta.validation.constraints.Pattern;

public record UserPreferencesPatchRequest(
        @Pattern(regexp = "^(?i)(light|dark)$", message = "must be either 'light' or 'dark'")
        String preferredTheme,
        Boolean encryptFilesByDefault
) {
}
