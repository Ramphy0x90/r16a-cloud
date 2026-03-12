package com.r16a.r16a_cloud.security;

import com.r16a.r16a_cloud.user.User;
import com.r16a.r16a_cloud.user.UserPreferences;
import com.r16a.r16a_cloud.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OidcJwtAuthenticationConverter implements Converter<Jwt, UsernamePasswordAuthenticationToken> {

    private final UserRepository userRepository;

    @Override
    public UsernamePasswordAuthenticationToken convert(Jwt jwt) {
        User user = syncUser(jwt);

        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
        return new UsernamePasswordAuthenticationToken(user, jwt, authorities);
    }

    private User syncUser(Jwt jwt) {
        String sub = jwt.getSubject();
        String username = jwt.getClaimAsString("preferred_username");
        String email = jwt.getClaimAsString("email");
        String displayName = jwt.getClaimAsString("given_name");

        return userRepository.findByExternalId(sub)
                .map(existing -> updateIfChanged(existing, username, email, displayName))
                .orElseGet(() -> {
                    User newUser = User.builder()
                            .externalId(sub)
                            .username(username)
                            .displayName(displayName)
                            .email(email)
                            .build();
                    log.info("New user created from IdP '{}'", username);
                    return userRepository.save(newUser);
                });
    }

    private User updateIfChanged(User user, String username, String email, String displayName) {
        boolean changed = false;

        if (user.getPreferences() == null) {
            user.setPreferences(UserPreferences.builder().build());
            changed = true;
        }

        if (username != null && !username.equals(user.getUsername())) {
            user.setUsername(username);
            changed = true;
        }

        if (email != null && !email.equals(user.getEmail())) {
            user.setEmail(email);
            changed = true;
        }

        if (displayName != null && !displayName.equals(user.getDisplayName())) {
            user.setDisplayName(displayName);
            changed = true;
        }

        return changed ? userRepository.save(user) : user;
    }
}
