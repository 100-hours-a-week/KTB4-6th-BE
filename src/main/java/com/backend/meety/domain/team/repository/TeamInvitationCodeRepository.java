package com.backend.meety.domain.team.repository;

import com.backend.meety.domain.team.entity.TeamInvitationCode;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamInvitationCodeRepository extends JpaRepository<TeamInvitationCode, Long> {

    boolean existsByCode(String code);
}
