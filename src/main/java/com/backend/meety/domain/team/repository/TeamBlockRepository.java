package com.backend.meety.domain.team.repository;

import com.backend.meety.domain.team.entity.TeamBlock;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamBlockRepository extends JpaRepository<TeamBlock, Long> {

    boolean existsByTeamIdAndUserIdAndDeletedAtIsNull(Long teamId, Long userId);

    List<TeamBlock> findAllByTeamIdAndDeletedAtIsNullOrderByIdAsc(Long teamId);
}
