package com.backend.meety.domain.credit.service;

import com.backend.meety.domain.credit.dto.TeamCreditResponse;
import com.backend.meety.domain.credit.entity.TeamCredit;
import com.backend.meety.domain.credit.exception.CreditErrorCode;
import com.backend.meety.domain.credit.exception.CreditException;
import com.backend.meety.domain.credit.repository.TeamCreditRepository;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.exception.TeamErrorCode;
import com.backend.meety.domain.team.exception.TeamException;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.domain.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TeamCreditService {

    private final TeamCreditRepository teamCreditRepository;
    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;

    @Transactional(readOnly = true)
    public TeamCreditResponse getBalance(Long userId, Long teamId) {
        teamRepository.findByIdAndDeletedAtIsNull(teamId)
                .orElseThrow(() -> new TeamException(TeamErrorCode.TEAM_NOT_FOUND));
        teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(teamId, userId, MembershipStatus.ACTIVE)
                .orElseThrow(() -> new TeamException(TeamErrorCode.TEAM_MEMBERSHIP_REQUIRED));
        try {
            TeamCredit credit = teamCreditRepository.findByTeamIdAndDeletedAtIsNull(teamId)
                    .orElseThrow(() -> {
                        log.error("팀 크레딧 행이 없습니다. teamId={}", teamId);
                        return new CreditException(CreditErrorCode.TEAM_CREDIT_NOT_FOUND);
                    });
            return TeamCreditResponse.from(credit);
        } catch (DataAccessException e) {
            throw new CreditException(CreditErrorCode.CREDIT_LOOKUP_FAILED);
        }
    }
}
