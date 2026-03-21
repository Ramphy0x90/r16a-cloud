package com.r16a.r16a_cloud.user;

import com.r16a.r16a_cloud.exception.ResourceAlreadyExistsException;
import com.r16a.r16a_cloud.exception.ResourceNotFoundException;
import com.r16a.r16a_cloud.user.dto.CreateUserRequest;
import com.r16a.r16a_cloud.user.dto.UpdateMyPreferencesRequest;
import com.r16a.r16a_cloud.user.dto.UpdateUserRequest;
import com.r16a.r16a_cloud.user.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    public Optional<User> findUserById(UUID id) {
        return userRepository.findById(id);
    }

    public User findUserByIdOrThrow(UUID id) {
        return findUserById(id).orElseThrow(
                () -> new ResourceNotFoundException("User", "id", id)
        );
    }

    public Optional<User> getUserByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    public User getUserByUsernameOrThrow(String username) {
        return userRepository.findByUsername(username).orElseThrow(
                () -> new ResourceNotFoundException("User", "username", username)
        );
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
        User user = findUserByIdOrThrow(id);

        if (request.displayName() != null) {
            user.setDisplayName(request.displayName());
        }

        if (request.role() != null) {
            user.setRole(request.role());
        }

        return UserResponse.from(userRepository.save(user));
    }

    public UserResponse updateMyPreferences(UUID currentUserId, UpdateMyPreferencesRequest request) {
        User user = findUserByIdOrThrow(currentUserId);
        UserPreferences preferences = user.getPreferences() != null
                ? user.getPreferences()
                : UserPreferences.builder().build();

        if (request.preferences().preferredTheme() != null) {
            preferences.setPreferredTheme(request.preferences().preferredTheme().toLowerCase());
        }

        if (request.preferences().encryptFilesByDefault() != null) {
            preferences.setEncryptFilesByDefault(request.preferences().encryptFilesByDefault());
        }

        if (request.preferences().defaultViewMode() != null) {
            preferences.setDefaultViewMode(request.preferences().defaultViewMode().toLowerCase());
        }

        user.setPreferences(preferences);
        return UserResponse.from(userRepository.save(user));
    }

    public void deleteUser(UUID id) {
        User user = findUserByIdOrThrow(id);
        userRepository.delete(user);
    }
}
