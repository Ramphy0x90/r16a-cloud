package com.r16a.r16a_cloud.file.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

public record DownloadFilesRequest(
        @NotEmpty List<UUID> ids
) {
}
