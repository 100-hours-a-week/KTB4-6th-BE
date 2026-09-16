package com.backend.meety.domain.auth.repository;

import com.backend.meety.domain.auth.entity.RefreshToken;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update RefreshToken rt set rt.deletedAt = :now "
            + "where rt.tokenHash = :tokenHash and rt.deletedAt is null")
    int markUsedIfActive(@Param("tokenHash") String tokenHash, @Param("now") LocalDateTime now);

    @Modifying
    @Query("update RefreshToken rt set rt.deletedAt = :now "
            + "where rt.userId = :userId and rt.deletedAt is null")
    void revokeAllByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
