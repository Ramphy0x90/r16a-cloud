package com.r16a.r16a_cloud.file;

import com.r16a.r16a_cloud.file.model.File;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FileRepository extends JpaRepository<File, Long> {
}
