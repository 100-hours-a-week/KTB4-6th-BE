package com.backend.meety.domain.credit.repository;

import com.backend.meety.domain.credit.entity.CreditLedger;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CreditLedgerRepository extends JpaRepository<CreditLedger, Long> {

    boolean existsByIdempotencyKey(String idempotencyKey);

    Optional<CreditLedger> findByIdempotencyKeyAndDeletedAtIsNull(String idempotencyKey);

    @Query("""
            select l
            from CreditLedger l
            where l.team.id = :teamId
              and l.id < :beforeLedgerId
              and l.deletedAt is null
            order by l.id desc
            """)
    List<CreditLedger> findPageByTeamId(
            @Param("teamId") Long teamId, @Param("beforeLedgerId") Long beforeLedgerId, Limit limit);
}
