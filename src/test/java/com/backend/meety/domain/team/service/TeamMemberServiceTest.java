package com.backend.meety.domain.team.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.backend.meety.domain.team.dto.TeamJoinRequest;
import com.backend.meety.domain.team.dto.MyTeamResponse;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamInvitationCode;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.exception.TeamErrorCode;
import com.backend.meety.domain.team.repository.TeamBlockRepository;
import com.backend.meety.domain.team.repository.TeamInvitationCodeRepository;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.domain.team.repository.TeamRepository;
import com.backend.meety.domain.user.entity.User;
import com.backend.meety.domain.user.repository.UserRepository;
import com.backend.meety.global.exception.BusinessException;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TeamMemberServiceTest {

    private static final String CODE = "ABCD1234";
    private static final TeamJoinRequest REQUEST = new TeamJoinRequest("abcd1234", "hoon");

    @Mock
    private UserRepository userRepository;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private TeamMemberRepository teamMemberRepository;

    @Mock
    private TeamInvitationCodeRepository teamInvitationCodeRepository;

    @Mock
    private TeamBlockRepository teamBlockRepository;

    @InjectMocks
    private TeamMemberService teamMemberService;

    private User user;
    private Team team;
    private TeamInvitationCode invitationCode;

    @BeforeEach
    void setUp() {
        user = User.create();
        team = Team.create("미티팀");
        ReflectionTestUtils.setField(team, "id", 7L);
        invitationCode = TeamInvitationCode.create(team, CODE);
    }

    @Test
    @DisplayName("소문자 코드를 입력해도 대문자로 정규화해 팀에 참여한다")
    void join() {
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(user));
        given(teamMemberRepository.existsByUserIdAndMembershipStatus(1L, MembershipStatus.ACTIVE))
                .willReturn(false);
        given(teamInvitationCodeRepository.findByCodeAndDeletedAtIsNull(CODE))
                .willReturn(Optional.of(invitationCode));
        given(teamRepository.findByIdForUpdate(7L)).willReturn(Optional.of(team));
        given(teamBlockRepository.existsByTeamIdAndUserIdAndDeletedAtIsNull(7L, 1L)).willReturn(false);
        given(teamMemberRepository.countByTeamIdAndMembershipStatus(7L, MembershipStatus.ACTIVE))
                .willReturn(3L);
        given(teamMemberRepository.existsByTeamIdAndDisplayName(7L, "hoon")).willReturn(false);
        given(teamMemberRepository.save(any(TeamMember.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        MyTeamResponse response = teamMemberService.join(1L, REQUEST);

        assertThat(response.hasActiveTeam()).isTrue();
        assertThat(response.teamId()).isEqualTo(7L);
        then(teamInvitationCodeRepository).should(times(2)).findByCodeAndDeletedAtIsNull(CODE);
    }

    @Test
    @DisplayName("사용자가 존재하지 않으면 참여할 수 없다")
    void failOnUnknownUser() {
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> teamMemberService.join(1L, REQUEST))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.USER_NOT_FOUND));
    }

    @Test
    @DisplayName("이미 활성 팀이 있으면 참여할 수 없다")
    void failOnActiveTeamExists() {
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(user));
        given(teamMemberRepository.existsByUserIdAndMembershipStatus(1L, MembershipStatus.ACTIVE))
                .willReturn(true);

        assertThatThrownBy(() -> teamMemberService.join(1L, REQUEST))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.ACTIVE_TEAM_ALREADY_EXISTS));
        then(teamInvitationCodeRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("없거나 폐기된 초대 코드로는 참여할 수 없다")
    void failOnUnknownCode() {
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(user));
        given(teamMemberRepository.existsByUserIdAndMembershipStatus(1L, MembershipStatus.ACTIVE))
                .willReturn(false);
        given(teamInvitationCodeRepository.findByCodeAndDeletedAtIsNull(CODE))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> teamMemberService.join(1L, REQUEST))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.INVITATION_CODE_NOT_FOUND));
    }

    @Test
    @DisplayName("삭제된 팀의 초대 코드로는 참여할 수 없다")
    void failOnDeletedTeam() {
        ReflectionTestUtils.setField(team, "deletedAt", LocalDateTime.of(2026, 9, 16, 10, 0));
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(user));
        given(teamMemberRepository.existsByUserIdAndMembershipStatus(1L, MembershipStatus.ACTIVE))
                .willReturn(false);
        given(teamInvitationCodeRepository.findByCodeAndDeletedAtIsNull(CODE))
                .willReturn(Optional.of(invitationCode));
        given(teamRepository.findByIdForUpdate(7L)).willReturn(Optional.of(team));

        assertThatThrownBy(() -> teamMemberService.join(1L, REQUEST))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.INVITATION_CODE_NOT_FOUND));
    }

    @Test
    @DisplayName("팀 잠금 획득 전에 코드가 재생성되었으면 참여할 수 없다")
    void failOnCodeRotatedBeforeLock() {
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(user));
        given(teamMemberRepository.existsByUserIdAndMembershipStatus(1L, MembershipStatus.ACTIVE))
                .willReturn(false);
        given(teamInvitationCodeRepository.findByCodeAndDeletedAtIsNull(CODE))
                .willReturn(Optional.of(invitationCode), Optional.empty());
        given(teamRepository.findByIdForUpdate(7L)).willReturn(Optional.of(team));

        assertThatThrownBy(() -> teamMemberService.join(1L, REQUEST))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.INVITATION_CODE_NOT_FOUND));
        then(teamMemberRepository).should(never()).save(any(TeamMember.class));
    }

    @Test
    @DisplayName("차단된 사용자는 참여할 수 없다")
    void failOnBlockedUser() {
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(user));
        given(teamMemberRepository.existsByUserIdAndMembershipStatus(1L, MembershipStatus.ACTIVE))
                .willReturn(false);
        given(teamInvitationCodeRepository.findByCodeAndDeletedAtIsNull(CODE))
                .willReturn(Optional.of(invitationCode));
        given(teamRepository.findByIdForUpdate(7L)).willReturn(Optional.of(team));
        given(teamBlockRepository.existsByTeamIdAndUserIdAndDeletedAtIsNull(7L, 1L)).willReturn(true);

        assertThatThrownBy(() -> teamMemberService.join(1L, REQUEST))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_BLOCKED_USER));
    }

    @Test
    @DisplayName("팀 정원이 가득 차면 참여할 수 없다")
    void failOnFullTeam() {
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(user));
        given(teamMemberRepository.existsByUserIdAndMembershipStatus(1L, MembershipStatus.ACTIVE))
                .willReturn(false);
        given(teamInvitationCodeRepository.findByCodeAndDeletedAtIsNull(CODE))
                .willReturn(Optional.of(invitationCode));
        given(teamRepository.findByIdForUpdate(7L)).willReturn(Optional.of(team));
        given(teamBlockRepository.existsByTeamIdAndUserIdAndDeletedAtIsNull(7L, 1L)).willReturn(false);
        given(teamMemberRepository.countByTeamIdAndMembershipStatus(7L, MembershipStatus.ACTIVE))
                .willReturn(10L);

        assertThatThrownBy(() -> teamMemberService.join(1L, REQUEST))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_MEMBER_LIMIT_EXCEEDED));
        then(teamMemberRepository).should(never()).save(any(TeamMember.class));
    }

    @Test
    @DisplayName("팀 내에서 사용된 적 있는 이름으로는 참여할 수 없다")
    void failOnDuplicatedDisplayName() {
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(user));
        given(teamMemberRepository.existsByUserIdAndMembershipStatus(1L, MembershipStatus.ACTIVE))
                .willReturn(false);
        given(teamInvitationCodeRepository.findByCodeAndDeletedAtIsNull(CODE))
                .willReturn(Optional.of(invitationCode));
        given(teamRepository.findByIdForUpdate(7L)).willReturn(Optional.of(team));
        given(teamBlockRepository.existsByTeamIdAndUserIdAndDeletedAtIsNull(7L, 1L)).willReturn(false);
        given(teamMemberRepository.countByTeamIdAndMembershipStatus(7L, MembershipStatus.ACTIVE))
                .willReturn(3L);
        given(teamMemberRepository.existsByTeamIdAndDisplayName(7L, "hoon")).willReturn(true);

        assertThatThrownBy(() -> teamMemberService.join(1L, REQUEST))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.DISPLAY_NAME_DUPLICATED));
    }

    @Test
    @DisplayName("멤버십 저장에 실패하면 참여에 실패한다")
    void failOnDataAccessError() {
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(user));
        given(teamMemberRepository.existsByUserIdAndMembershipStatus(1L, MembershipStatus.ACTIVE))
                .willReturn(false);
        given(teamInvitationCodeRepository.findByCodeAndDeletedAtIsNull(CODE))
                .willReturn(Optional.of(invitationCode));
        given(teamRepository.findByIdForUpdate(7L)).willReturn(Optional.of(team));
        given(teamBlockRepository.existsByTeamIdAndUserIdAndDeletedAtIsNull(7L, 1L)).willReturn(false);
        given(teamMemberRepository.countByTeamIdAndMembershipStatus(7L, MembershipStatus.ACTIVE))
                .willReturn(3L);
        given(teamMemberRepository.existsByTeamIdAndDisplayName(7L, "hoon")).willReturn(false);
        given(teamMemberRepository.save(any(TeamMember.class)))
                .willThrow(new DataIntegrityViolationException("insert failed"));

        assertThatThrownBy(() -> teamMemberService.join(1L, REQUEST))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_MEMBERSHIP_CREATE_FAILED));
    }
}
