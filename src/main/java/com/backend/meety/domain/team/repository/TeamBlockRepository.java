package com.backend.meety.domain.team.repository;

import com.backend.meety.domain.team.entity.TeamBlock;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamBlockRepository extends JpaRepository<TeamBlock, Long> {

    boolean existsByTeamIdAndUserIdAndDeletedAtIsNull(Long teamId, Long userId);
}
