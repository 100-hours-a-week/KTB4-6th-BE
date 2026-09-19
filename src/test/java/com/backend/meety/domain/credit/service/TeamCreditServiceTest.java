package com.backend.meety.domain.credit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.backend.meety.domain.credit.CreditPolicy;
import com.backend.meety.domain.credit.dto.TeamCreditResponse;
import com.backend.meety.domain.credit.entity.TeamCredit;
import com.backend.meety.domain.credit.exception.CreditErrorCode;
import com.backend.meety.domain.credit.repository.TeamCreditRepository;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.exception.TeamErrorCode;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.domain.team.repository.TeamRepository;
import com.backend.meety.domain.user.entity.User;
import com.backend.meety.global.exception.BusinessException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class TeamCreditServiceTest {

    @Mock
    private TeamCreditRepository teamCreditRepository;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private TeamMemberRepository teamMemberRepository;

    @InjectMocks
    private TeamCreditService teamCreditService;

    private final Team team = Team.create("Meety Team");

    @Test
    @DisplayName("팀원은 팀 크레딧 잔액과 상한을 조회한다")
    void getBalance() {
        given(teamRepository.findByIdAndDeletedAtIsNull(7L)).willReturn(Optional.of(team));
        given(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(7L, 1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.of(TeamMember.createMember(User.create(), team, "hoon")));
        given(teamCreditRepository.findByTeamIdAndDeletedAtIsNull(7L))
                .willReturn(Optional.of(TeamCredit.create(team, 270L)));

        TeamCreditResponse response = teamCreditService.getBalance(1L, 7L);

        assertThat(response.balance()).isEqualTo(270L);
        assertThat(response.maxBalance()).isEqualTo(CreditPolicy.MAX_BALANCE);
    }

    @Test
    @DisplayName("존재하지 않는 팀의 크레딧은 조회할 수 없다")
    void failOnUnknownTeam() {
        given(teamRepository.findByIdAndDeletedAtIsNull(7L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> teamCreditService.getBalance(1L, 7L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_NOT_FOUND));
    }

    @Test
    @DisplayName("활성 팀원이 아니면 크레딧을 조회할 수 없다")
    void failOnNonMember() {
        given(teamRepository.findByIdAndDeletedAtIsNull(7L)).willReturn(Optional.of(team));
        given(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(7L, 1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> teamCreditService.getBalance(1L, 7L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_MEMBERSHIP_REQUIRED));
    }

    @Test
    @DisplayName("크레딧 행이 없으면 조회에 실패한다")
    void failOnMissingCreditRow() {
        given(teamRepository.findByIdAndDeletedAtIsNull(7L)).willReturn(Optional.of(team));
        given(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(7L, 1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.of(TeamMember.createMember(User.create(), team, "hoon")));
        given(teamCreditRepository.findByTeamIdAndDeletedAtIsNull(7L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> teamCreditService.getBalance(1L, 7L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CreditErrorCode.TEAM_CREDIT_NOT_FOUND));
    }

    @Test
    @DisplayName("조회 중 DB 오류가 나면 크레딧 조회에 실패한다")
    void failOnDataAccessError() {
        given(teamRepository.findByIdAndDeletedAtIsNull(7L)).willReturn(Optional.of(team));
        given(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(7L, 1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.of(TeamMember.createMember(User.create(), team, "hoon")));
        given(teamCreditRepository.findByTeamIdAndDeletedAtIsNull(7L))
                .willThrow(new DataIntegrityViolationException("select failed"));

        assertThatThrownBy(() -> teamCreditService.getBalance(1L, 7L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CreditErrorCode.CREDIT_LOOKUP_FAILED));
    }
}
