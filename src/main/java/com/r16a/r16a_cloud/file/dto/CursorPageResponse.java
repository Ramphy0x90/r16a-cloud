package com.r16a.r16a_cloud.file.dto;

import java.util.List;

public record CursorPageResponse<T>(
        List<T> content,
        String nextCursor,
        boolean hasMore
) {}
