package com.backend.meety.domain.credit.service;

import static com.backend.meety.domain.recording.RecordingFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.backend.meety.domain.credit.CreditLedgerPagePolicy;
import com.backend.meety.domain.credit.TestCreditPolicy;
import com.backend.meety.domain.credit.dto.CreditLedgerListResponse;
import com.backend.meety.domain.credit.dto.CreditLedgerResponse;
import com.backend.meety.domain.credit.dto.TeamCreditResponse;
import com.backend.meety.domain.credit.entity.CreditLedger;
import com.backend.meety.domain.credit.entity.CreditSourceType;
import com.backend.meety.domain.credit.entity.CreditTransactionType;
import com.backend.meety.domain.credit.entity.TeamCredit;
import com.backend.meety.domain.credit.exception.CreditErrorCode;
import com.backend.meety.domain.credit.repository.CreditLedgerRepository;
import com.backend.meety.domain.credit.repository.TeamCreditRepository;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.exception.TeamErrorCode;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.domain.team.repository.TeamRepository;
import com.backend.meety.domain.user.entity.User;
import com.backend.meety.global.exception.BusinessException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;

@ExtendWith(MockitoExtension.class)
class TeamCreditServiceTest {

    @Mock
    private TeamCreditRepository teamCreditRepository;

    @Mock
    private CreditLedgerRepository creditLedgerRepository;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private TeamMemberRepository teamMemberRepository;

    private TeamCreditService teamCreditService;

    private final Team team = Team.create("Meety Team");

    @BeforeEach
    void setUp() {
        teamCreditService = new TeamCreditService(
                teamCreditRepository, creditLedgerRepository, teamRepository, teamMemberRepository,
                TestCreditPolicy.DEFAULT);
    }

    @Test
    @DisplayName("팀원은 팀 크레딧 잔액과 상한을 조회한다")
    void getBalance() {
        given(teamRepository.findByIdAndDeletedAtIsNull(7L)).willReturn(Optional.of(team));
        given(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(7L, 1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.of(TeamMember.createMember(User.create(), team, "hoon")));
        given(teamCreditRepository.findByTeamIdAndDeletedAtIsNull(7L))
                .willReturn(Optional.of(TeamCredit.create(team, 270L, TestCreditPolicy.MAX_BALANCE)));

        TeamCreditResponse response = teamCreditService.getBalance(1L, 7L);

        assertThat(response.balance()).isEqualTo(270L);
        assertThat(response.maxBalance()).isEqualTo(TestCreditPolicy.MAX_BALANCE);
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

    @Test
    @DisplayName("팀원은 크레딧 원장을 최신순으로 조회하고 다음 페이지 커서를 받는다")
    void getLedgers() {
        givenActiveMember();
        given(creditLedgerRepository.findPageByTeamId(7L, Long.MAX_VALUE, Limit.of(3)))
                .willReturn(List.of(
                        withId(CreditLedger.useForChat(team, 30L, 1L, 270L), 1042L),
                        withId(CreditLedger.earnForSchedule(team, "2026-W35", 50L, 271L), 1041L),
                        withId(CreditLedger.useForRecording(team, 9L, 20L, 221L), 1040L)));

        CreditLedgerListResponse response = teamCreditService.getLedgers(1L, 7L, null, 2);

        assertThat(response.ledgers()).extracting(CreditLedgerResponse::ledgerId).containsExactly(1042L, 1041L);
        assertThat(response.ledgers()).extracting(CreditLedgerResponse::amount).containsExactly(-1L, 50L);
        assertThat(response.ledgers().getFirst().type()).isEqualTo(CreditTransactionType.USE);
        assertThat(response.ledgers().getFirst().sourceType()).isEqualTo(CreditSourceType.AI_CHAT);
        assertThat(response.ledgers().getFirst().balanceAfter()).isEqualTo(270L);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isEqualTo(1041L);
    }

    @Test
    @DisplayName("커서 이전 원장을 기본 크기로 조회하고 마지막 페이지면 커서가 없다")
    void getLastLedgerPage() {
        givenActiveMember();
        given(creditLedgerRepository.findPageByTeamId(7L, 1041L,
                Limit.of(CreditLedgerPagePolicy.DEFAULT_PAGE_SIZE + 1)))
                .willReturn(List.of(withId(CreditLedger.earnForSchedule(team, "2026-W30", 50L, 300L), 1L)));

        CreditLedgerListResponse response = teamCreditService.getLedgers(1L, 7L, 1041L, null);

        assertThat(response.ledgers()).extracting(CreditLedgerResponse::ledgerId).containsExactly(1L);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    @DisplayName("커서가 1보다 작으면 원장을 조회할 수 없다")
    void failOnInvalidLedgerCursor() {
        assertThatThrownBy(() -> teamCreditService.getLedgers(1L, 7L, 0L, 10))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CreditErrorCode.INVALID_CURSOR));
    }

    @Test
    @DisplayName("페이지 크기가 1~50을 벗어나면 원장을 조회할 수 없다")
    void failOnInvalidLedgerPageSize() {
        assertThatThrownBy(() -> teamCreditService.getLedgers(1L, 7L, null, 0))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CreditErrorCode.INVALID_PAGE_SIZE));
        assertThatThrownBy(() -> teamCreditService.getLedgers(1L, 7L, null, CreditLedgerPagePolicy.MAX_PAGE_SIZE + 1))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CreditErrorCode.INVALID_PAGE_SIZE));
    }

    @Test
    @DisplayName("활성 팀원이 아니면 크레딧 원장을 조회할 수 없다")
    void failOnLedgerNonMember() {
        given(teamRepository.findByIdAndDeletedAtIsNull(7L)).willReturn(Optional.of(team));
        given(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(7L, 1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> teamCreditService.getLedgers(1L, 7L, null, 10))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_MEMBERSHIP_REQUIRED));
    }

    @Test
    @DisplayName("원장 조회 중 DB 오류가 나면 원장 조회에 실패한다")
    void failOnLedgerDataAccessError() {
        givenActiveMember();
        given(creditLedgerRepository.findPageByTeamId(7L, Long.MAX_VALUE, Limit.of(11)))
                .willThrow(new DataIntegrityViolationException("select failed"));

        assertThatThrownBy(() -> teamCreditService.getLedgers(1L, 7L, null, 10))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CreditErrorCode.CREDIT_LEDGER_LOOKUP_FAILED));
    }

    private void givenActiveMember() {
        given(teamRepository.findByIdAndDeletedAtIsNull(7L)).willReturn(Optional.of(team));
        given(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(7L, 1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.of(TeamMember.createMember(User.create(), team, "hoon")));
    }
}
