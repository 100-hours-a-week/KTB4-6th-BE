package com.backend.meety.domain.team.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.backend.meety.domain.team.dto.MyTeamResponse;
import com.backend.meety.domain.team.dto.TeamJoinRequest;
import com.backend.meety.domain.team.dto.TeamBlockListResponse;
import com.backend.meety.domain.team.dto.TeamMemberListResponse;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamBlock;
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
        given(teamMemberRepository.existsActiveDisplayName(7L, "hoon", MembershipStatus.ACTIVE)).willReturn(false);
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
        given(teamMemberRepository.existsActiveDisplayName(7L, "hoon", MembershipStatus.ACTIVE)).willReturn(true);

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
        given(teamMemberRepository.existsActiveDisplayName(7L, "hoon", MembershipStatus.ACTIVE)).willReturn(false);
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

    @Test
    @DisplayName("팀장이 팀원을 강퇴하면 KICKED 처리되고 차단 목록에 등록된다")
    void kick() {
        User leaderUser = user;
        ReflectionTestUtils.setField(leaderUser, "id", 1L);
        User targetUser = User.create();
        ReflectionTestUtils.setField(targetUser, "id", 2L);
        TeamMember leader = TeamMember.createLeader(leaderUser, team, "leader");
        TeamMember target = TeamMember.createMember(targetUser, team, "hoon");
        given(teamRepository.findByIdForUpdate(7L)).willReturn(Optional.of(team));
        given(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(7L, 1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.of(leader));
        given(teamMemberRepository.findById(10L)).willReturn(Optional.of(target));

        teamMemberService.kick(1L, 7L, 10L);

        assertThat(target.getMembershipStatus()).isEqualTo(MembershipStatus.KICKED);
        assertThat(target.getDeletedAt()).isNotNull();
        then(teamBlockRepository).should().save(any(com.backend.meety.domain.team.entity.TeamBlock.class));
    }

    @Test
    @DisplayName("팀장이 아니면 강퇴할 수 없다")
    void kickFailOnNonLeader() {
        User memberUser = user;
        ReflectionTestUtils.setField(memberUser, "id", 1L);
        TeamMember member = TeamMember.createMember(memberUser, team, "hoon");
        given(teamRepository.findByIdForUpdate(7L)).willReturn(Optional.of(team));
        given(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(7L, 1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.of(member));

        assertThatThrownBy(() -> teamMemberService.kick(1L, 7L, 10L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_LEADER_REQUIRED));
        then(teamBlockRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("존재하지 않는 팀원은 강퇴할 수 없다")
    void kickFailOnUnknownMember() {
        ReflectionTestUtils.setField(user, "id", 1L);
        TeamMember leader = TeamMember.createLeader(user, team, "leader");
        given(teamRepository.findByIdForUpdate(7L)).willReturn(Optional.of(team));
        given(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(7L, 1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.of(leader));
        given(teamMemberRepository.findById(10L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> teamMemberService.kick(1L, 7L, 10L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_MEMBER_NOT_FOUND));
    }

    @Test
    @DisplayName("다른 팀 소속 팀원은 강퇴할 수 없다")
    void kickFailOnOtherTeamMember() {
        ReflectionTestUtils.setField(user, "id", 1L);
        TeamMember leader = TeamMember.createLeader(user, team, "leader");
        Team otherTeam = Team.create("다른팀");
        ReflectionTestUtils.setField(otherTeam, "id", 8L);
        TeamMember target = TeamMember.createMember(User.create(), otherTeam, "hoon");
        given(teamRepository.findByIdForUpdate(7L)).willReturn(Optional.of(team));
        given(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(7L, 1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.of(leader));
        given(teamMemberRepository.findById(10L)).willReturn(Optional.of(target));

        assertThatThrownBy(() -> teamMemberService.kick(1L, 7L, 10L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_MEMBER_NOT_FOUND));
    }

    @Test
    @DisplayName("자기 자신은 강퇴할 수 없다")
    void kickFailOnSelf() {
        ReflectionTestUtils.setField(user, "id", 1L);
        TeamMember leader = TeamMember.createLeader(user, team, "leader");
        given(teamRepository.findByIdForUpdate(7L)).willReturn(Optional.of(team));
        given(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(7L, 1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.of(leader));
        given(teamMemberRepository.findById(10L)).willReturn(Optional.of(leader));

        assertThatThrownBy(() -> teamMemberService.kick(1L, 7L, 10L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.CANNOT_KICK_SELF));
    }

    @Test
    @DisplayName("이미 나간 팀원은 강퇴할 수 없다")
    void kickFailOnInactiveMember() {
        ReflectionTestUtils.setField(user, "id", 1L);
        User targetUser = User.create();
        ReflectionTestUtils.setField(targetUser, "id", 2L);
        TeamMember leader = TeamMember.createLeader(user, team, "leader");
        TeamMember target = TeamMember.createMember(targetUser, team, "hoon");
        target.leave(LocalDateTime.of(2026, 9, 17, 10, 0));
        given(teamRepository.findByIdForUpdate(7L)).willReturn(Optional.of(team));
        given(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(7L, 1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.of(leader));
        given(teamMemberRepository.findById(10L)).willReturn(Optional.of(target));

        assertThatThrownBy(() -> teamMemberService.kick(1L, 7L, 10L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_MEMBER_NOT_ACTIVE));
        then(teamBlockRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("차단 등록에 실패하면 강퇴에 실패한다")
    void kickFailOnDataAccessError() {
        ReflectionTestUtils.setField(user, "id", 1L);
        User targetUser = User.create();
        ReflectionTestUtils.setField(targetUser, "id", 2L);
        TeamMember leader = TeamMember.createLeader(user, team, "leader");
        TeamMember target = TeamMember.createMember(targetUser, team, "hoon");
        given(teamRepository.findByIdForUpdate(7L)).willReturn(Optional.of(team));
        given(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(7L, 1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.of(leader));
        given(teamMemberRepository.findById(10L)).willReturn(Optional.of(target));
        given(teamBlockRepository.save(any(com.backend.meety.domain.team.entity.TeamBlock.class)))
                .willThrow(new DataIntegrityViolationException("insert failed"));

        assertThatThrownBy(() -> teamMemberService.kick(1L, 7L, 10L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_MEMBER_KICK_FAILED));
    }

    @Test
    @DisplayName("팀장은 차단 목록을 최신 이력 이름과 함께 조회한다")
    void getBlocks() {
        ReflectionTestUtils.setField(user, "id", 1L);
        TeamMember leader = TeamMember.createLeader(user, team, "leader");
        User blockedUser = User.create();
        ReflectionTestUtils.setField(blockedUser, "id", 2L);
        TeamBlock block = TeamBlock.create(team, blockedUser);
        ReflectionTestUtils.setField(block, "id", 50L);
        TeamMember oldMembership = TeamMember.createMember(blockedUser, team, "옛이름");
        ReflectionTestUtils.setField(oldMembership, "id", 5L);
        TeamMember latestMembership = TeamMember.createMember(blockedUser, team, "새이름");
        ReflectionTestUtils.setField(latestMembership, "id", 9L);
        given(teamRepository.findByIdAndDeletedAtIsNull(7L)).willReturn(Optional.of(team));
        given(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(7L, 1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.of(leader));
        given(teamBlockRepository.findAllByTeamIdAndDeletedAtIsNullOrderByIdAsc(7L))
                .willReturn(List.of(block));
        given(teamMemberRepository.findAllByTeamIdAndUserIdIn(7L, List.of(2L)))
                .willReturn(List.of(oldMembership, latestMembership));

        TeamBlockListResponse response = teamMemberService.getBlocks(1L, 7L);

        assertThat(response.blocks()).hasSize(1);
        assertThat(response.blocks().get(0).blockId()).isEqualTo(50L);
        assertThat(response.blocks().get(0).userId()).isEqualTo(2L);
        assertThat(response.blocks().get(0).displayName()).isEqualTo("새이름");
    }

    @Test
    @DisplayName("팀장이 아니면 차단 목록을 조회할 수 없다")
    void getBlocksFailOnNonLeader() {
        ReflectionTestUtils.setField(user, "id", 1L);
        TeamMember member = TeamMember.createMember(user, team, "hoon");
        given(teamRepository.findByIdAndDeletedAtIsNull(7L)).willReturn(Optional.of(team));
        given(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(7L, 1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.of(member));

        assertThatThrownBy(() -> teamMemberService.getBlocks(1L, 7L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_LEADER_REQUIRED));
    }

    @Test
    @DisplayName("팀장이 차단을 해제하면 차단 행이 논리 삭제되고 멤버십이 LEFT로 바뀐다")
    void unblock() {
        ReflectionTestUtils.setField(user, "id", 1L);
        TeamMember leader = TeamMember.createLeader(user, team, "leader");
        User blockedUser = User.create();
        ReflectionTestUtils.setField(blockedUser, "id", 2L);
        TeamBlock block = TeamBlock.create(team, blockedUser);
        TeamMember kicked = TeamMember.createMember(blockedUser, team, "hoon");
        kicked.kick(LocalDateTime.of(2026, 9, 17, 10, 0));
        given(teamRepository.findByIdForUpdate(7L)).willReturn(Optional.of(team));
        given(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(7L, 1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.of(leader));
        given(teamBlockRepository.findById(50L)).willReturn(Optional.of(block));
        given(teamMemberRepository.findByTeamIdAndUserId(7L, 2L)).willReturn(Optional.of(kicked));

        teamMemberService.unblock(1L, 7L, 50L);

        assertThat(block.getDeletedAt()).isNotNull();
        assertThat(kicked.getMembershipStatus()).isEqualTo(MembershipStatus.LEFT);
        assertThat(kicked.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("이미 해제된 차단은 다시 해제할 수 없다")
    void unblockFailOnAlreadyReleased() {
        ReflectionTestUtils.setField(user, "id", 1L);
        TeamMember leader = TeamMember.createLeader(user, team, "leader");
        TeamBlock block = TeamBlock.create(team, User.create());
        block.release(LocalDateTime.of(2026, 9, 17, 10, 0));
        given(teamRepository.findByIdForUpdate(7L)).willReturn(Optional.of(team));
        given(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(7L, 1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.of(leader));
        given(teamBlockRepository.findById(50L)).willReturn(Optional.of(block));

        assertThatThrownBy(() -> teamMemberService.unblock(1L, 7L, 50L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_BLOCK_NOT_FOUND));
    }

    @Test
    @DisplayName("다른 팀의 차단은 해제할 수 없다")
    void unblockFailOnOtherTeamBlock() {
        ReflectionTestUtils.setField(user, "id", 1L);
        TeamMember leader = TeamMember.createLeader(user, team, "leader");
        Team otherTeam = Team.create("다른팀");
        ReflectionTestUtils.setField(otherTeam, "id", 8L);
        TeamBlock block = TeamBlock.create(otherTeam, User.create());
        given(teamRepository.findByIdForUpdate(7L)).willReturn(Optional.of(team));
        given(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(7L, 1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.of(leader));
        given(teamBlockRepository.findById(50L)).willReturn(Optional.of(block));

        assertThatThrownBy(() -> teamMemberService.unblock(1L, 7L, 50L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_BLOCK_NOT_FOUND));
    }

    @Test
    @DisplayName("차단 해제 후 재입장하면 기존 멤버십 행이 복구된다")
    void rejoinRestoresExistingRow() {
        ReflectionTestUtils.setField(user, "id", 1L);
        TeamMember existing = TeamMember.createMember(user, team, "옛이름");
        existing.kick(LocalDateTime.of(2026, 9, 17, 10, 0));
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(user));
        given(teamMemberRepository.existsByUserIdAndMembershipStatus(1L, MembershipStatus.ACTIVE))
                .willReturn(false);
        given(teamInvitationCodeRepository.findByCodeAndDeletedAtIsNull(CODE))
                .willReturn(Optional.of(invitationCode));
        given(teamRepository.findByIdForUpdate(7L)).willReturn(Optional.of(team));
        given(teamBlockRepository.existsByTeamIdAndUserIdAndDeletedAtIsNull(7L, 1L)).willReturn(false);
        given(teamMemberRepository.countByTeamIdAndMembershipStatus(7L, MembershipStatus.ACTIVE))
                .willReturn(3L);
        given(teamMemberRepository.existsActiveDisplayName(7L, "hoon", MembershipStatus.ACTIVE))
                .willReturn(false);
        given(teamMemberRepository.findByTeamIdAndUserId(7L, 1L)).willReturn(Optional.of(existing));

        teamMemberService.join(1L, REQUEST);

        assertThat(existing.getMembershipStatus()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(existing.getDisplayName()).isEqualTo("hoon");
        assertThat(existing.getDeletedAt()).isNull();
        then(teamMemberRepository).should(never()).save(any(TeamMember.class));
    }

    @Test
    @DisplayName("팀장이 위임하면 자신은 MEMBER가 되고 대상이 LEADER가 된다")
    void delegateLeader() {
        ReflectionTestUtils.setField(user, "id", 1L);
        TeamMember leader = TeamMember.createLeader(user, team, "leader");
        ReflectionTestUtils.setField(leader, "id", 1L);
        TeamMember target = TeamMember.createMember(User.create(), team, "hoon");
        ReflectionTestUtils.setField(target, "id", 10L);
        given(teamRepository.findByIdForUpdate(7L)).willReturn(Optional.of(team));
        given(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(7L, 1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.of(leader));
        given(teamMemberRepository.findById(10L)).willReturn(Optional.of(target));

        teamMemberService.delegateLeader(1L, 7L, 10L);

        assertThat(leader.isLeader()).isFalse();
        assertThat(target.isLeader()).isTrue();
    }

    @Test
    @DisplayName("존재하지 않는 팀에서는 위임할 수 없다")
    void delegateFailOnUnknownTeam() {
        given(teamRepository.findByIdForUpdate(7L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> teamMemberService.delegateLeader(1L, 7L, 10L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_NOT_FOUND));
    }

    @Test
    @DisplayName("팀장이 아니면 위임할 수 없다")
    void delegateFailOnNonLeader() {
        ReflectionTestUtils.setField(user, "id", 1L);
        TeamMember member = TeamMember.createMember(user, team, "hoon");
        given(teamRepository.findByIdForUpdate(7L)).willReturn(Optional.of(team));
        given(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(7L, 1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.of(member));

        assertThatThrownBy(() -> teamMemberService.delegateLeader(1L, 7L, 10L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_LEADER_REQUIRED));
    }

    @Test
    @DisplayName("자기 자신에게는 위임할 수 없다")
    void delegateFailOnSelf() {
        ReflectionTestUtils.setField(user, "id", 1L);
        TeamMember leader = TeamMember.createLeader(user, team, "leader");
        ReflectionTestUtils.setField(leader, "id", 1L);
        given(teamRepository.findByIdForUpdate(7L)).willReturn(Optional.of(team));
        given(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(7L, 1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.of(leader));
        given(teamMemberRepository.findById(1L)).willReturn(Optional.of(leader));

        assertThatThrownBy(() -> teamMemberService.delegateLeader(1L, 7L, 1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.LEADER_ALREADY_ASSIGNED));
        assertThat(leader.isLeader()).isTrue();
    }

    @Test
    @DisplayName("비활성 팀원에게는 위임할 수 없다")
    void delegateFailOnInactiveTarget() {
        ReflectionTestUtils.setField(user, "id", 1L);
        TeamMember leader = TeamMember.createLeader(user, team, "leader");
        ReflectionTestUtils.setField(leader, "id", 1L);
        TeamMember target = TeamMember.createMember(User.create(), team, "hoon");
        ReflectionTestUtils.setField(target, "id", 10L);
        target.leave(LocalDateTime.of(2026, 9, 17, 10, 0));
        given(teamRepository.findByIdForUpdate(7L)).willReturn(Optional.of(team));
        given(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(7L, 1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.of(leader));
        given(teamMemberRepository.findById(10L)).willReturn(Optional.of(target));

        assertThatThrownBy(() -> teamMemberService.delegateLeader(1L, 7L, 10L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TARGET_TEAM_MEMBER_NOT_ACTIVE));
        assertThat(leader.isLeader()).isTrue();
    }

    @Test
    @DisplayName("다른 팀 소속에게는 위임할 수 없다")
    void delegateFailOnOtherTeamTarget() {
        ReflectionTestUtils.setField(user, "id", 1L);
        TeamMember leader = TeamMember.createLeader(user, team, "leader");
        ReflectionTestUtils.setField(leader, "id", 1L);
        Team otherTeam = Team.create("다른팀");
        ReflectionTestUtils.setField(otherTeam, "id", 8L);
        TeamMember target = TeamMember.createMember(User.create(), otherTeam, "hoon");
        ReflectionTestUtils.setField(target, "id", 10L);
        given(teamRepository.findByIdForUpdate(7L)).willReturn(Optional.of(team));
        given(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(7L, 1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.of(leader));
        given(teamMemberRepository.findById(10L)).willReturn(Optional.of(target));

        assertThatThrownBy(() -> teamMemberService.delegateLeader(1L, 7L, 10L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_MEMBER_NOT_FOUND));
    }
}
