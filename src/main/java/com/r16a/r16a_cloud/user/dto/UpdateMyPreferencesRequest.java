package com.r16a.r16a_cloud.user.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record UpdateMyPreferencesRequest(
        @NotNull
        @Valid
        UserPreferencesPatchRequest preferences
) {
}
