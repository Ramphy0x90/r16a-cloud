package com.r16a.r16a_cloud.user.dto;

import com.r16a.r16a_cloud.user.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
        @Email String email,
        @Size(max = 100) String displayName,
        Role role
) {
}
