package com.r16a.r16a_cloud.file.dto;

import java.util.List;

public record FileEventsResponse(List<FileEventDto> events, long nextCursor, boolean hasMore) {}
