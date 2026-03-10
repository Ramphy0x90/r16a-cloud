package com.r16a.r16a_cloud.file;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
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
}
