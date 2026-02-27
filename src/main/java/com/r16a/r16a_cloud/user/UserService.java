package com.r16a.r16a_cloud.user;

import com.r16a.r16a_cloud.exception.ResourceAlreadyExistsException;
import com.r16a.r16a_cloud.exception.ResourceNotFoundException;
import com.r16a.r16a_cloud.user.dto.CreateUserRequest;
import com.r16a.r16a_cloud.user.dto.UpdateUserRequest;
import com.r16a.r16a_cloud.user.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    public UserResponse getUserById(UUID id) {
        return UserResponse.from(findUserOrThrow(id));
    }

    public UserResponse getUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .map(UserResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("User", "username", username));
    }

    public Page<UserResponse> getAllUsers(Pageable pageable) {
        return userRepository.findAll(pageable).map(UserResponse::from);
    }

    public UserResponse createUser(CreateUserRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new ResourceAlreadyExistsException("User", "username", request.username());
        }

        User user = User.builder()
                .username(request.username())
                .displayName(request.displayName())
                .role(request.role() != null ? request.role() : Role.USER)
                .build();

        return UserResponse.from(userRepository.save(user));
    }

    public UserResponse updateUser(UUID id, UpdateUserRequest request) {
        User user = findUserOrThrow(id);

        if (request.displayName() != null) {
            user.setDisplayName(request.displayName());
        }

        if (request.role() != null) {
            user.setRole(request.role());
        }

        return UserResponse.from(userRepository.save(user));
    }

    public void deleteUser(UUID id) {
        userRepository.delete(findUserOrThrow(id));
    }

    private User findUserOrThrow(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
    }
}
