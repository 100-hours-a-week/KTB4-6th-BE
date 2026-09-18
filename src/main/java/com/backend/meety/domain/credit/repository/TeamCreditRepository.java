package com.backend.meety.domain.credit.repository;

import com.backend.meety.domain.credit.entity.TeamCredit;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TeamCreditRepository extends JpaRepository<TeamCredit, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from TeamCredit c where c.team.id = :teamId and c.deletedAt is null")
    Optional<TeamCredit> findByTeamIdForUpdate(@Param("teamId") Long teamId);
}
