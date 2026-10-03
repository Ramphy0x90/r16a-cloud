package com.r16a.r16a_cloud.file;

import java.time.Instant;

/**
 * What a folder listing's ETag is derived from. The child count catches deletes and moves-out,
 * which leave the newest {@code updatedAt} unchanged; {@code maxUpdatedAt} catches creates, renames
 * and moves-in. {@code maxUpdatedAt} is null for an empty folder.
 */
public record FolderVersion(Long childCount, Instant maxUpdatedAt) {
}
