package com.backend.meety.domain.auth.repository;

import com.backend.meety.domain.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
}
