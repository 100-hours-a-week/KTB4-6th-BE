package com.backend.meety.domain.credit.service;

import com.backend.meety.domain.credit.CreditPolicy;
import com.backend.meety.domain.credit.entity.CreditLedger;
import com.backend.meety.domain.credit.entity.TeamCredit;
import com.backend.meety.domain.credit.repository.CreditLedgerRepository;
import com.backend.meety.domain.credit.repository.TeamCreditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class WeeklyCreditGrantService {

    private final TeamCreditRepository teamCreditRepository;
    private final CreditLedgerRepository creditLedgerRepository;
    private final CreditPolicy creditPolicy;

    @Transactional
    public boolean grant(Long teamId, String weekKey) {
        TeamCredit credit = teamCreditRepository.findByTeamIdForUpdate(teamId).orElse(null);
        if (credit == null) {
            log.error("팀 크레딧 행이 없습니다. teamId={}", teamId);
            return false;
        }
        if (creditLedgerRepository.existsByIdempotencyKey(CreditLedger.scheduleKeyOf(teamId, weekKey))) {
            return false;
        }
        long earned = credit.earn(creditPolicy.weeklyGrant(), creditPolicy.maxBalance());
        if (earned == 0) {
            return false;
        }
        creditLedgerRepository.save(
                CreditLedger.earnForSchedule(credit.getTeam(), weekKey, earned, credit.getBalance()));
        return true;
    }
}
