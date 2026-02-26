package com.r16a.r16a_cloud.file;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
