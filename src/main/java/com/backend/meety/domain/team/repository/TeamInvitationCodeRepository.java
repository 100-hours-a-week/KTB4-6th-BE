package com.backend.meety.domain.team.repository;

import com.backend.meety.domain.team.entity.TeamInvitationCode;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TeamInvitationCodeRepository extends JpaRepository<TeamInvitationCode, Long> {

    Optional<TeamInvitationCode> findByTeamIdAndDeletedAtIsNull(Long teamId);

    Optional<TeamInvitationCode> findByCodeAndDeletedAtIsNull(String code);

    boolean existsByCode(String code);

    @Modifying
    @Query("update TeamInvitationCode tic set tic.deletedAt = :now "
            + "where tic.team.id = :teamId and tic.deletedAt is null")
    void revokeAllByTeamId(@Param("teamId") Long teamId, @Param("now") LocalDateTime now);
}
