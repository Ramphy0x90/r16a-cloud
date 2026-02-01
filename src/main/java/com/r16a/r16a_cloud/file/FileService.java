package com.r16a.r16a_cloud.file;

import com.r16a.r16a_cloud.exception.ResourceAlreadyExistsException;
import com.r16a.r16a_cloud.exception.ResourceNotFoundException;
import com.r16a.r16a_cloud.file.dto.CreateFileRequest;
import com.r16a.r16a_cloud.file.dto.FileResponse;
import com.r16a.r16a_cloud.file.dto.UpdateFileRequest;
import com.r16a.r16a_cloud.user.User;
import com.r16a.r16a_cloud.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.HashSet;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    private final FileRepository fileRepository;
    private final UserRepository userRepository;

    public FileResponse createFile(CreateFileRequest request) {
        User owner = userRepository.findById(request.ownerId())
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", request.ownerId()));

        File parent = null;
        String fsPath;

        if (request.parentId() != null) {
            parent = findFileOrThrow(request.parentId());
            checkDuplicateName(request.name(), request.parentId(), owner.getId());
            fsPath = parent.getFsPath() + "/" + request.name();
        } else {
            checkDuplicateNameAtRoot(request.name(), owner.getId());
            fsPath = "/" + request.name();
        }

        File file = File.builder()
                .name(request.name())
                .description(request.description())
                .fsPath(fsPath)
                .directory(request.directory())
                .size(request.directory() ? null : request.size())
                .mimeType(request.directory() ? null : request.mimeType())
                .visibility(request.visibility() != null ? request.visibility() : Visibility.PRIVATE)
                .parent(parent)
                .owner(owner)
                .sharedWith(resolveUsers(request.sharedWithIds()))
                .build();

        return FileResponse.from(fileRepository.save(file));
    }

    public FileResponse getFileById(Long id) {
        return FileResponse.from(findFileOrThrow(id));
    }

    public Page<FileResponse> listFiles(Long ownerId, Long parentId, Pageable pageable) {
        if (!userRepository.existsById(ownerId)) {
            throw new ResourceNotFoundException("User", "id", ownerId);
        }

        Page<File> files;
        if (parentId != null) {
            findFileOrThrow(parentId);
            files = fileRepository.findByParentIdAndOwnerId(parentId, ownerId, pageable);
        } else {
            files = fileRepository.findByParentIsNullAndOwnerId(ownerId, pageable);
        }

        return files.map(FileResponse::from);
    }

    public FileResponse updateFile(Long id, UpdateFileRequest request) {
        File file = findFileOrThrow(id);

        if (request.name() != null) {
            Long parentId = request.parentId() != null
                    ? request.parentId()
                    : (file.getParent() != null ? file.getParent().getId() : null);

            if (!request.name().equals(file.getName()) || request.parentId() != null) {
                if (parentId != null) {
                    checkDuplicateName(request.name(), parentId, file.getOwner().getId());
                } else {
                    checkDuplicateNameAtRoot(request.name(), file.getOwner().getId());
                }
            }

            file.setName(request.name());
        }

        if (request.description() != null) {
            file.setDescription(request.description());
        }

        if (request.parentId() != null) {
            File newParent = findFileOrThrow(request.parentId());
            file.setParent(newParent);
            file.setFsPath(newParent.getFsPath() + "/" + file.getName());
        } else if (request.name() != null) {
            String parentPath = file.getParent() != null ? file.getParent().getFsPath() : "";
            file.setFsPath(parentPath + "/" + file.getName());
        }

        if (request.visibility() != null) {
            file.setVisibility(request.visibility());
        }

        if (request.sharedWithIds() != null) {
            file.setSharedWith(resolveUsers(request.sharedWithIds()));
        }

        return FileResponse.from(fileRepository.save(file));
    }

    public void deleteFile(Long id) {
        fileRepository.delete(findFileOrThrow(id));
    }

    private File findFileOrThrow(Long id) {
        return fileRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("File", "id", id));
    }

    private void checkDuplicateName(String name, Long parentId, Long ownerId) {
        if (fileRepository.existsByNameAndParentIdAndOwnerId(name, parentId, ownerId)) {
            throw new ResourceAlreadyExistsException("File", "name", name);
        }
    }

    private void checkDuplicateNameAtRoot(String name, Long ownerId) {
        if (fileRepository.existsByNameAndParentIsNullAndOwnerId(name, ownerId)) {
            throw new ResourceAlreadyExistsException("File", "name", name);
        }
    }

    private Set<User> resolveUsers(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return new HashSet<>();
        }
        Set<User> users = new HashSet<>(userRepository.findAllById(userIds));
        if (users.size() != userIds.size()) {
            throw new ResourceNotFoundException("User", "ids", userIds);
        }
        return users;
    }
}
