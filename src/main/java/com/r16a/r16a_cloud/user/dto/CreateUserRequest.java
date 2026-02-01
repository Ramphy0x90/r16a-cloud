package com.r16a.r16a_cloud.user.dto;

import com.r16a.r16a_cloud.user.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank @Size(min = 3, max = 50) String username,
        @NotBlank @Email String email,
        @NotBlank @Size(max = 100) String displayName,
        @Size(min = 8, max = 128) String password,
        Role role
) {
}
