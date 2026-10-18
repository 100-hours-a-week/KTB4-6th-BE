package com.backend.meety.domain.credit.service;

import com.backend.meety.domain.credit.CreditLedgerPagePolicy;
import com.backend.meety.domain.credit.CreditPolicy;
import com.backend.meety.domain.credit.dto.CreditLedgerListResponse;
import com.backend.meety.domain.credit.dto.TeamCreditResponse;
import com.backend.meety.domain.credit.entity.CreditLedger;
import com.backend.meety.domain.credit.entity.TeamCredit;
import com.backend.meety.domain.credit.exception.CreditErrorCode;
import com.backend.meety.domain.credit.exception.CreditException;
import com.backend.meety.domain.credit.repository.CreditLedgerRepository;
import com.backend.meety.domain.credit.repository.TeamCreditRepository;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.exception.TeamErrorCode;
import com.backend.meety.domain.team.exception.TeamException;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.domain.team.repository.TeamRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TeamCreditService {

    private final TeamCreditRepository teamCreditRepository;
    private final CreditLedgerRepository creditLedgerRepository;
    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final CreditPolicy creditPolicy;

    @Transactional(readOnly = true)
    public TeamCreditResponse getBalance(Long userId, Long teamId) {
        validateActiveMember(userId, teamId);
        try {
            TeamCredit credit = teamCreditRepository.findByTeamIdAndDeletedAtIsNull(teamId)
                    .orElseThrow(() -> {
                        log.error("팀 크레딧 행이 없습니다. teamId={}", teamId);
                        return new CreditException(CreditErrorCode.TEAM_CREDIT_NOT_FOUND);
                    });
            return TeamCreditResponse.from(credit, creditPolicy);
        } catch (DataAccessException e) {
            throw new CreditException(CreditErrorCode.CREDIT_LOOKUP_FAILED);
        }
    }

    @Transactional(readOnly = true)
    public CreditLedgerListResponse getLedgers(Long userId, Long teamId, Long cursor, Integer size) {
        long beforeLedgerId = resolveCursor(cursor);
        int pageSize = resolvePageSize(size);
        validateActiveMember(userId, teamId);
        try {
            List<CreditLedger> fetched = creditLedgerRepository.findPageByTeamId(
                    teamId, beforeLedgerId, Limit.of(pageSize + 1));
            return CreditLedgerListResponse.of(fetched, pageSize);
        } catch (DataAccessException e) {
            log.error("크레딧 원장 조회에 실패했습니다. teamId={}", teamId, e);
            throw new CreditException(CreditErrorCode.CREDIT_LEDGER_LOOKUP_FAILED);
        }
    }

    private void validateActiveMember(Long userId, Long teamId) {
        teamRepository.findByIdAndDeletedAtIsNull(teamId)
                .orElseThrow(() -> new TeamException(TeamErrorCode.TEAM_NOT_FOUND));
        teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(teamId, userId, MembershipStatus.ACTIVE)
                .orElseThrow(() -> new TeamException(TeamErrorCode.TEAM_MEMBERSHIP_REQUIRED));
    }

    private long resolveCursor(Long cursor) {
        if (cursor == null) {
            return Long.MAX_VALUE;
        }
        if (cursor < 1) {
            throw new CreditException(CreditErrorCode.INVALID_CURSOR);
        }
        return cursor;
    }

    private int resolvePageSize(Integer size) {
        if (size == null) {
            return CreditLedgerPagePolicy.DEFAULT_PAGE_SIZE;
        }
        if (size < 1 || size > CreditLedgerPagePolicy.MAX_PAGE_SIZE) {
            throw new CreditException(CreditErrorCode.INVALID_PAGE_SIZE);
        }
        return size;
    }
}
