package com.r16a.r16a_cloud.file;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FileRepository extends JpaRepository<File, Long> {

    Page<File> findByParentIdAndOwnerId(Long parentId, Long ownerId, Pageable pageable);

    Page<File> findByParentIsNullAndOwnerId(Long ownerId, Pageable pageable);

    boolean existsByNameAndParentIdAndOwnerId(String name, Long parentId, Long ownerId);

    boolean existsByNameAndParentIsNullAndOwnerId(String name, Long ownerId);

    boolean existsByNameAndParentIdAndOwnerIdAndIdNot(String name, Long parentId, Long ownerId, Long id);

    boolean existsByNameAndParentIsNullAndOwnerIdAndIdNot(String name, Long ownerId, Long id);

    List<File> findByParentId(Long parentId);
}
