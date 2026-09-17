package com.backend.meety.domain.team.repository;

import com.backend.meety.domain.team.entity.Team;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TeamRepository extends JpaRepository<Team, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Team t where t.id = :teamId")
    Optional<Team> findByIdForUpdate(@Param("teamId") Long teamId);

    Optional<Team> findByIdAndDeletedAtIsNull(Long teamId);
}
