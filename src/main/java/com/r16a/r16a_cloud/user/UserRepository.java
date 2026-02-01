package com.r16a.r16a_cloud.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    Optional<User> findByExternalId(String externalId);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);
}
