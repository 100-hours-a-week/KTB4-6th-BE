package com.backend.meety.domain.team.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.backend.meety.domain.team.dto.TeamJoinRequest;
import com.backend.meety.domain.team.dto.TeamMemberListResponse;
import com.backend.meety.domain.team.dto.TeamJoinResponse;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamInvitationCode;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.entity.TeamMemberRole;
import com.backend.meety.domain.team.exception.TeamErrorCode;
import com.backend.meety.domain.team.repository.TeamBlockRepository;
import com.backend.meety.domain.team.repository.TeamInvitationCodeRepository;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.domain.team.repository.TeamRepository;
import com.backend.meety.domain.user.entity.User;
import com.backend.meety.domain.user.repository.UserRepository;
import com.backend.meety.global.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TeamMemberServiceTest {

    private static final String CODE = "ABCD1234";
    private static final TeamJoinRequest REQUEST = new TeamJoinRequest("abcd1234", "hoon");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-17T03:00:00Z"), ZoneId.of("Asia/Seoul"));

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

    private TeamMemberService teamMemberService;

    private User user;
    private Team team;
    private TeamInvitationCode invitationCode;

    @BeforeEach
    void setUp() {
        teamMemberService = new TeamMemberService(userRepository, teamRepository,
                teamMemberRepository, teamInvitationCodeRepository, teamBlockRepository, FIXED_CLOCK);
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

        TeamJoinResponse response = teamMemberService.join(1L, REQUEST);

        assertThat(response.teamId()).isEqualTo(7L);
        assertThat(response.teamName()).isEqualTo("미티팀");
        assertThat(response.displayName()).isEqualTo("hoon");
        assertThat(response.role()).isEqualTo(TeamMemberRole.MEMBER);
        assertThat(response.membershipStatus()).isEqualTo(MembershipStatus.ACTIVE);
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

    @Test
    @DisplayName("팀원은 활성 팀원 목록을 가입순으로 조회한다")
    void getMembers() {
        TeamMember leader = TeamMember.createLeader(user, team, "leader");
        TeamMember member = TeamMember.createMember(User.create(), team, "hoon");
        given(teamRepository.findByIdAndDeletedAtIsNull(7L)).willReturn(Optional.of(team));
        given(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(7L, 1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.of(leader));
        given(teamMemberRepository.findAllActiveOrderByLeaderFirst(7L, MembershipStatus.ACTIVE))
                .willReturn(List.of(leader, member));

        TeamMemberListResponse response = teamMemberService.getMembers(1L, 7L);

        assertThat(response.members()).hasSize(2);
        assertThat(response.members().get(0).displayName()).isEqualTo("leader");
        assertThat(response.members().get(1).displayName()).isEqualTo("hoon");
    }

    @Test
    @DisplayName("팀원이 아니면 팀원 목록을 조회할 수 없다")
    void getMembersFailOnNonMember() {
        given(teamRepository.findByIdAndDeletedAtIsNull(7L)).willReturn(Optional.of(team));
        given(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(7L, 1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> teamMemberService.getMembers(1L, 7L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_MEMBERSHIP_REQUIRED));
    }

    @Test
    @DisplayName("존재하지 않는 팀의 팀원 목록은 조회할 수 없다")
    void getMembersFailOnUnknownTeam() {
        given(teamRepository.findByIdAndDeletedAtIsNull(7L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> teamMemberService.getMembers(1L, 7L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_NOT_FOUND));
    }

    @Test
    @DisplayName("목록 조회 중 DB 오류가 나면 팀원 목록 조회에 실패한다")
    void getMembersFailOnDataAccessError() {
        given(teamRepository.findByIdAndDeletedAtIsNull(7L)).willReturn(Optional.of(team));
        given(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(7L, 1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.of(TeamMember.createLeader(user, team, "leader")));
        given(teamMemberRepository.findAllActiveOrderByLeaderFirst(7L, MembershipStatus.ACTIVE))
                .willThrow(new DataIntegrityViolationException("select failed"));

        assertThatThrownBy(() -> teamMemberService.getMembers(1L, 7L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_MEMBER_LOOKUP_FAILED));
    }

    @Test
    @DisplayName("팀원이 나가면 멤버십이 LEFT로 바뀌고 떠난 시각이 기록된다")
    void leave() {
        TeamMember member = TeamMember.createMember(user, team, "hoon");
        given(teamRepository.findByIdForUpdate(7L)).willReturn(Optional.of(team));
        given(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(7L, 1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.of(member));

        teamMemberService.leave(1L, 7L);

        assertThat(member.getMembershipStatus()).isEqualTo(MembershipStatus.LEFT);
        assertThat(member.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("팀장은 팀을 나갈 수 없다")
    void leaveFailOnLeader() {
        TeamMember leader = TeamMember.createLeader(user, team, "leader");
        given(teamRepository.findByIdForUpdate(7L)).willReturn(Optional.of(team));
        given(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(7L, 1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.of(leader));

        assertThatThrownBy(() -> teamMemberService.leave(1L, 7L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.LEADER_CANNOT_LEAVE_TEAM));
        assertThat(leader.getMembershipStatus()).isEqualTo(MembershipStatus.ACTIVE);
    }

    @Test
    @DisplayName("팀원이 아니면 팀을 나갈 수 없다")
    void leaveFailOnNonMember() {
        given(teamRepository.findByIdForUpdate(7L)).willReturn(Optional.of(team));
        given(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(7L, 1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> teamMemberService.leave(1L, 7L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_MEMBERSHIP_REQUIRED));
    }

    @Test
    @DisplayName("삭제된 팀에서는 나가기 요청이 실패한다")
    void leaveFailOnDeletedTeam() {
        ReflectionTestUtils.setField(team, "deletedAt", LocalDateTime.of(2026, 9, 16, 10, 0));
        given(teamRepository.findByIdForUpdate(7L)).willReturn(Optional.of(team));

        assertThatThrownBy(() -> teamMemberService.leave(1L, 7L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_NOT_FOUND));
    }
}
