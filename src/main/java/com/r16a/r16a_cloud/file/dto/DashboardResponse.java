package com.r16a.r16a_cloud.file.dto;

import java.util.List;

public record DashboardResponse(
        DashboardMetricsResponse metrics,
        List<RecentFileItemResponse> recentFiles
) {
}
