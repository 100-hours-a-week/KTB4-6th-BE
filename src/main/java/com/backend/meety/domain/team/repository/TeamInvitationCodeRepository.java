package com.backend.meety.domain.team.repository;

import com.backend.meety.domain.team.entity.TeamInvitationCode;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamInvitationCodeRepository extends JpaRepository<TeamInvitationCode, Long> {

    Optional<TeamInvitationCode> findByTeamIdAndDeletedAtIsNull(Long teamId);

    Optional<TeamInvitationCode> findByCodeAndDeletedAtIsNull(String code);

    boolean existsByCode(String code);
}
