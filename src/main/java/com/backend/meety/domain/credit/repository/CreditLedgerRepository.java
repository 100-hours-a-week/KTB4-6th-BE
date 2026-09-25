package com.backend.meety.domain.credit.repository;

import com.backend.meety.domain.credit.entity.CreditLedger;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreditLedgerRepository extends JpaRepository<CreditLedger, Long> {

    boolean existsByIdempotencyKey(String idempotencyKey);
}
