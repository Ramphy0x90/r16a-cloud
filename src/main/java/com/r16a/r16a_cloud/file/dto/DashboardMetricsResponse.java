package com.r16a.r16a_cloud.file.dto;

public record DashboardMetricsResponse(
        long uploadedFiles,
        long usedStorageBytes,
        long sharedFiles
) {
}
