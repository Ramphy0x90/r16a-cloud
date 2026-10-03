package com.r16a.r16a_cloud.user;

import com.r16a.r16a_cloud.exception.GlobalExceptionHandler;
import com.r16a.r16a_cloud.security.OidcJwtAuthenticationConverter;
import com.r16a.r16a_cloud.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class UserControllerSecurityTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private OidcJwtAuthenticationConverter oidcJwtAuthenticationConverter;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void regularUserCannotDeleteAnotherUser() throws Exception {
        mockMvc.perform(delete("/api/user/{id}", UUID.randomUUID())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanDeleteUser() throws Exception {
        mockMvc.perform(delete("/api/user/{id}", UUID.randomUUID())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNoContent());
    }
}
