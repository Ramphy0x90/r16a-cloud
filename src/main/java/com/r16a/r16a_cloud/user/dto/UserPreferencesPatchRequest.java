package com.r16a.r16a_cloud.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Pattern;

/**
 * Unknown fields are ignored so clients still sending the removed
 * {@code encryptFilesByDefault} preference keep working.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserPreferencesPatchRequest(
        @Pattern(regexp = "^(?i)(light|dark)$", message = "must be either 'light' or 'dark'")
        String preferredTheme,
        @Pattern(regexp = "^(?i)(grid|list)$", message = "must be either 'grid' or 'list'")
        String defaultViewMode
) {
}
