package com.r16a.r16a_cloud.file;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FileRepository extends JpaRepository<File, UUID> {

    Page<File> findByParentIdAndOwnerId(UUID parentId, UUID ownerId, Pageable pageable);

    Page<File> findByParentIsNullAndOwnerId(UUID ownerId, Pageable pageable);

    boolean existsByNameAndParentIdAndOwnerId(String name, UUID parentId, UUID ownerId);

    boolean existsByNameAndParentIsNullAndOwnerId(String name, UUID ownerId);

    boolean existsByNameAndParentIdAndOwnerIdAndIdNot(String name, UUID parentId, UUID ownerId, UUID id);

    boolean existsByNameAndParentIsNullAndOwnerIdAndIdNot(String name, UUID ownerId, UUID id);

    List<File> findByParentId(UUID parentId);

    long countByOwnerIdAndIsDirectoryFalse(UUID ownerId);

    @Query("""
            select coalesce(sum(f.sizeBytes), 0)
            from File f
            where f.owner.id = :ownerId
              and f.isDirectory = false
            """)
    long sumFileSizeBytesByOwnerId(@Param("ownerId") UUID ownerId);

    @Query("""
            select count(distinct f.id)
            from File f
            join f.sharedWith sharedUser
            where f.owner.id = :ownerId
              and f.isDirectory = false
            """)
    long countSharedFilesByOwnerId(@Param("ownerId") UUID ownerId);

    List<File> findTop5ByOwnerIdAndIsDirectoryFalseOrderByUpdatedAtDesc(UUID ownerId);

    @Query("""
            select distinct f
            from File f
            join f.sharedWith sharedUser
            where sharedUser.id = :userId
              and f.owner.id <> :userId
            """)
    Page<File> findFilesSharedWithUser(@Param("userId") UUID userId, Pageable pageable);

    // --- Slice-based first-page queries (no COUNT, avoids expensive total-count query) ---

    Slice<File> findSliceByParentIdAndOwnerId(UUID parentId, UUID ownerId, Pageable pageable);

    Slice<File> findSliceByParentIsNullAndOwnerId(UUID ownerId, Pageable pageable);

    // --- ETag: max updatedAt for conditional GET on folder listings ---

    @Query("SELECT MAX(f.updatedAt) FROM File f WHERE f.owner.id = :ownerId AND f.parent.id = :parentId")
    Optional<Instant> findMaxUpdatedAtByOwnerIdAndParentId(@Param("ownerId") UUID ownerId, @Param("parentId") UUID parentId);

    @Query("SELECT MAX(f.updatedAt) FROM File f WHERE f.owner.id = :ownerId AND f.parent IS NULL")
    Optional<Instant> findMaxUpdatedAtByOwnerIdAndParentIsNull(@Param("ownerId") UUID ownerId);

    // --- Keyset cursor queries: name ASC ---

    @Query("""
            SELECT f FROM File f WHERE f.owner.id = :ownerId AND f.parent.id = :parentId
            AND (
                (:lastIsDir = true AND f.isDirectory = false)
                OR (:lastIsDir = true AND f.isDirectory = true
                    AND (f.name > :lastVal OR (f.name = :lastVal AND f.id > :lastId)))
                OR (:lastIsDir = false AND f.isDirectory = false
                    AND (f.name > :lastVal OR (f.name = :lastVal AND f.id > :lastId)))
            )
            ORDER BY f.isDirectory DESC, f.name ASC, f.id ASC
            """)
    Slice<File> findCursorNameAscByParentId(@Param("ownerId") UUID ownerId, @Param("parentId") UUID parentId,
            @Param("lastIsDir") boolean lastIsDir, @Param("lastVal") String lastVal, @Param("lastId") UUID lastId,
            Pageable pageable);

    @Query("""
            SELECT f FROM File f WHERE f.owner.id = :ownerId AND f.parent IS NULL
            AND (
                (:lastIsDir = true AND f.isDirectory = false)
                OR (:lastIsDir = true AND f.isDirectory = true
                    AND (f.name > :lastVal OR (f.name = :lastVal AND f.id > :lastId)))
                OR (:lastIsDir = false AND f.isDirectory = false
                    AND (f.name > :lastVal OR (f.name = :lastVal AND f.id > :lastId)))
            )
            ORDER BY f.isDirectory DESC, f.name ASC, f.id ASC
            """)
    Slice<File> findCursorNameAscRoot(@Param("ownerId") UUID ownerId,
            @Param("lastIsDir") boolean lastIsDir, @Param("lastVal") String lastVal, @Param("lastId") UUID lastId,
            Pageable pageable);

    // --- Keyset cursor queries: name DESC ---

    @Query("""
            SELECT f FROM File f WHERE f.owner.id = :ownerId AND f.parent.id = :parentId
            AND (
                (:lastIsDir = true AND f.isDirectory = false)
                OR (:lastIsDir = true AND f.isDirectory = true
                    AND (f.name < :lastVal OR (f.name = :lastVal AND f.id > :lastId)))
                OR (:lastIsDir = false AND f.isDirectory = false
                    AND (f.name < :lastVal OR (f.name = :lastVal AND f.id > :lastId)))
            )
            ORDER BY f.isDirectory DESC, f.name DESC, f.id ASC
            """)
    Slice<File> findCursorNameDescByParentId(@Param("ownerId") UUID ownerId, @Param("parentId") UUID parentId,
            @Param("lastIsDir") boolean lastIsDir, @Param("lastVal") String lastVal, @Param("lastId") UUID lastId,
            Pageable pageable);

    @Query("""
            SELECT f FROM File f WHERE f.owner.id = :ownerId AND f.parent IS NULL
            AND (
                (:lastIsDir = true AND f.isDirectory = false)
                OR (:lastIsDir = true AND f.isDirectory = true
                    AND (f.name < :lastVal OR (f.name = :lastVal AND f.id > :lastId)))
                OR (:lastIsDir = false AND f.isDirectory = false
                    AND (f.name < :lastVal OR (f.name = :lastVal AND f.id > :lastId)))
            )
            ORDER BY f.isDirectory DESC, f.name DESC, f.id ASC
            """)
    Slice<File> findCursorNameDescRoot(@Param("ownerId") UUID ownerId,
            @Param("lastIsDir") boolean lastIsDir, @Param("lastVal") String lastVal, @Param("lastId") UUID lastId,
            Pageable pageable);

    // --- Keyset cursor queries: updatedAt ASC ---

    @Query("""
            SELECT f FROM File f WHERE f.owner.id = :ownerId AND f.parent.id = :parentId
            AND (
                (:lastIsDir = true AND f.isDirectory = false)
                OR (:lastIsDir = true AND f.isDirectory = true
                    AND (f.updatedAt > :lastVal OR (f.updatedAt = :lastVal AND f.id > :lastId)))
                OR (:lastIsDir = false AND f.isDirectory = false
                    AND (f.updatedAt > :lastVal OR (f.updatedAt = :lastVal AND f.id > :lastId)))
            )
            ORDER BY f.isDirectory DESC, f.updatedAt ASC, f.id ASC
            """)
    Slice<File> findCursorUpdatedAtAscByParentId(@Param("ownerId") UUID ownerId, @Param("parentId") UUID parentId,
            @Param("lastIsDir") boolean lastIsDir, @Param("lastVal") Instant lastVal, @Param("lastId") UUID lastId,
            Pageable pageable);

    @Query("""
            SELECT f FROM File f WHERE f.owner.id = :ownerId AND f.parent IS NULL
            AND (
                (:lastIsDir = true AND f.isDirectory = false)
                OR (:lastIsDir = true AND f.isDirectory = true
                    AND (f.updatedAt > :lastVal OR (f.updatedAt = :lastVal AND f.id > :lastId)))
                OR (:lastIsDir = false AND f.isDirectory = false
                    AND (f.updatedAt > :lastVal OR (f.updatedAt = :lastVal AND f.id > :lastId)))
            )
            ORDER BY f.isDirectory DESC, f.updatedAt ASC, f.id ASC
            """)
    Slice<File> findCursorUpdatedAtAscRoot(@Param("ownerId") UUID ownerId,
            @Param("lastIsDir") boolean lastIsDir, @Param("lastVal") Instant lastVal, @Param("lastId") UUID lastId,
            Pageable pageable);

    // --- Keyset cursor queries: updatedAt DESC ---

    @Query("""
            SELECT f FROM File f WHERE f.owner.id = :ownerId AND f.parent.id = :parentId
            AND (
                (:lastIsDir = true AND f.isDirectory = false)
                OR (:lastIsDir = true AND f.isDirectory = true
                    AND (f.updatedAt < :lastVal OR (f.updatedAt = :lastVal AND f.id > :lastId)))
                OR (:lastIsDir = false AND f.isDirectory = false
                    AND (f.updatedAt < :lastVal OR (f.updatedAt = :lastVal AND f.id > :lastId)))
            )
            ORDER BY f.isDirectory DESC, f.updatedAt DESC, f.id ASC
            """)
    Slice<File> findCursorUpdatedAtDescByParentId(@Param("ownerId") UUID ownerId, @Param("parentId") UUID parentId,
            @Param("lastIsDir") boolean lastIsDir, @Param("lastVal") Instant lastVal, @Param("lastId") UUID lastId,
            Pageable pageable);

    @Query("""
            SELECT f FROM File f WHERE f.owner.id = :ownerId AND f.parent IS NULL
            AND (
                (:lastIsDir = true AND f.isDirectory = false)
                OR (:lastIsDir = true AND f.isDirectory = true
                    AND (f.updatedAt < :lastVal OR (f.updatedAt = :lastVal AND f.id > :lastId)))
                OR (:lastIsDir = false AND f.isDirectory = false
                    AND (f.updatedAt < :lastVal OR (f.updatedAt = :lastVal AND f.id > :lastId)))
            )
            ORDER BY f.isDirectory DESC, f.updatedAt DESC, f.id ASC
            """)
    Slice<File> findCursorUpdatedAtDescRoot(@Param("ownerId") UUID ownerId,
            @Param("lastIsDir") boolean lastIsDir, @Param("lastVal") Instant lastVal, @Param("lastId") UUID lastId,
            Pageable pageable);
}
