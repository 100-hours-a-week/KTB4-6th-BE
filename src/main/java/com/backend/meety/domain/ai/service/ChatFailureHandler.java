package com.backend.meety.domain.ai.service;

import com.backend.meety.domain.ai.entity.AiFailureReason;
import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.credit.CreditPolicy;
import com.backend.meety.domain.credit.entity.CreditLedger;
import com.backend.meety.domain.credit.entity.CreditSourceType;
import com.backend.meety.domain.credit.entity.CreditTransactionType;
import com.backend.meety.domain.credit.entity.TeamCredit;
import com.backend.meety.domain.credit.repository.CreditLedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ChatFailureHandler {

    private final CreditLedgerRepository creditLedgerRepository;
    private final CreditPolicy creditPolicy;

    public void fail(AiRequest request, TeamCredit credit) {
        request.markFailed(AiFailureReason.AI_CALL_FAILED);
        restoreCreditIfCharged(request, credit);
    }

    private void restoreCreditIfCharged(AiRequest request, TeamCredit credit) {
        String useLedgerKey = CreditLedger.keyOf(CreditTransactionType.USE, CreditSourceType.AI_CHAT, request.getId());
        if (!creditLedgerRepository.existsByIdempotencyKey(useLedgerKey)) {
            return;
        }
        long restoredAmount = credit.earn(creditPolicy.aiChatMessageCost(), creditPolicy.maxBalance());
        creditLedgerRepository.save(CreditLedger.restoreForChat(
                request.getTeam(), request.getId(), restoredAmount, credit.getBalance()));
    }
}
