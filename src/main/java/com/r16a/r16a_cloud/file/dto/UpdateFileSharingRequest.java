package com.r16a.r16a_cloud.file.dto;

import jakarta.validation.constraints.NotNull;

import java.util.Set;
import java.util.UUID;

public record UpdateFileSharingRequest(
        @NotNull
        Set<UUID> sharedWithIds
) {
}
