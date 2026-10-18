package com.backend.meety.domain.credit.event;

public record CreditEarnedEvent(
        Long teamId,
        String creditLedgerIdempotencyKey,
        Long earnedAmount,
        CreditEarnedReason reason
) {
}
