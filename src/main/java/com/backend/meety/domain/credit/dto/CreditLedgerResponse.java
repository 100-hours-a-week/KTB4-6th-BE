package com.backend.meety.domain.credit.dto;

import com.backend.meety.domain.credit.entity.CreditLedger;
import com.backend.meety.domain.credit.entity.CreditSourceType;
import com.backend.meety.domain.credit.entity.CreditTransactionType;
import java.time.LocalDateTime;

public record CreditLedgerResponse(
        Long ledgerId,
        CreditTransactionType type,
        CreditSourceType sourceType,
        Long amount,
        Long balanceAfter,
        LocalDateTime createdAt
) {

    public static CreditLedgerResponse from(CreditLedger ledger) {
        return new CreditLedgerResponse(
                ledger.getId(),
                ledger.getType(),
                ledger.getSourceType(),
                ledger.getAmount(),
                ledger.getBalanceAfter(),
                ledger.getCreatedAt());
    }
}
