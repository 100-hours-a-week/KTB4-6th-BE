package com.backend.meety.domain.team.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.backend.meety.domain.team.dto.MyTeamResponse;
import com.backend.meety.domain.team.dto.TeamCreateRequest;
import com.backend.meety.domain.team.dto.TeamCreateResponse;
import com.backend.meety.domain.team.dto.TeamDetailResponse;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamInvitationCode;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.entity.TeamMemberRole;
import com.backend.meety.domain.team.exception.TeamErrorCode;
import com.backend.meety.domain.team.repository.TeamInvitationCodeRepository;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.domain.team.repository.TeamRepository;
import com.backend.meety.domain.user.entity.User;
import com.backend.meety.domain.user.repository.UserRepository;
import com.backend.meety.global.exception.BusinessException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TeamServiceTest {

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private TeamMemberRepository teamMemberRepository;

    @Mock
    private TeamInvitationCodeRepository teamInvitationCodeRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private TeamService teamService;

    @Test
    @DisplayName("팀을 생성하면 팀장 멤버십과 초대 코드가 함께 생성된다")
    void create() {
        User user = User.create();
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(user));
        given(teamMemberRepository.existsByUserIdAndMembershipStatus(1L, MembershipStatus.ACTIVE))
                .willReturn(false);
        given(teamRepository.save(any(Team.class))).willAnswer(invocation -> invocation.getArgument(0));
        given(teamMemberRepository.save(any(TeamMember.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(teamInvitationCodeRepository.save(any(TeamInvitationCode.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(teamInvitationCodeRepository.existsByCode(anyString())).willReturn(false);

        TeamCreateResponse response = teamService.create(1L, new TeamCreateRequest("Meety Team", "jay"));

        assertThat(response.name()).isEqualTo("Meety Team");
        assertThat(response.displayName()).isEqualTo("jay");
        assertThat(response.role()).isEqualTo(TeamMemberRole.LEADER);
        assertThat(response.membershipStatus()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(response.invitationCode()).matches("[A-Z0-9]{8}");
    }

    @Test
    @DisplayName("이미 활성 팀이 있으면 팀을 생성할 수 없다")
    void failOnActiveTeamExists() {
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(User.create()));
        given(teamMemberRepository.existsByUserIdAndMembershipStatus(1L, MembershipStatus.ACTIVE))
                .willReturn(true);

        assertThatThrownBy(() -> teamService.create(1L, new TeamCreateRequest("Meety Team", "jay")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.ACTIVE_TEAM_ALREADY_EXISTS));
        then(teamRepository).should(never()).save(any(Team.class));
    }

    @Test
    @DisplayName("사용자가 존재하지 않으면 팀을 생성할 수 없다")
    void failOnUnknownUser() {
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> teamService.create(1L, new TeamCreateRequest("Meety Team", "jay")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.USER_NOT_FOUND));
        then(teamRepository).should(never()).save(any(Team.class));
    }

    @Test
    @DisplayName("초대 코드가 충돌하면 새 코드로 재시도한다")
    void retryOnInvitationCodeCollision() {
        User user = User.create();
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(user));
        given(teamMemberRepository.existsByUserIdAndMembershipStatus(1L, MembershipStatus.ACTIVE))
                .willReturn(false);
        given(teamRepository.save(any(Team.class))).willAnswer(invocation -> invocation.getArgument(0));
        given(teamMemberRepository.save(any(TeamMember.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(teamInvitationCodeRepository.save(any(TeamInvitationCode.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(teamInvitationCodeRepository.existsByCode(anyString()))
                .willReturn(true, false);

        TeamCreateResponse response = teamService.create(1L, new TeamCreateRequest("Meety Team", "jay"));

        assertThat(response.invitationCode()).matches("[A-Z0-9]{8}");
        then(teamInvitationCodeRepository).should(times(2)).existsByCode(anyString());
    }

    @Test
    @DisplayName("초대 코드 생성이 계속 충돌하면 팀 생성에 실패한다")
    void failOnExhaustedInvitationCodeAttempts() {
        User user = User.create();
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(user));
        given(teamMemberRepository.existsByUserIdAndMembershipStatus(1L, MembershipStatus.ACTIVE))
                .willReturn(false);
        given(teamRepository.save(any(Team.class))).willAnswer(invocation -> invocation.getArgument(0));
        given(teamMemberRepository.save(any(TeamMember.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(teamInvitationCodeRepository.existsByCode(anyString())).willReturn(true);

        assertThatThrownBy(() -> teamService.create(1L, new TeamCreateRequest("Meety Team", "jay")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_CREATE_FAILED));
        then(teamInvitationCodeRepository).should(never()).save(any(TeamInvitationCode.class));
    }

    @Test
    @DisplayName("DB 저장에 실패하면 팀 생성에 실패한다")
    void failOnDataAccessError() {
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(User.create()));
        given(teamMemberRepository.existsByUserIdAndMembershipStatus(1L, MembershipStatus.ACTIVE))
                .willReturn(false);
        given(teamRepository.save(any(Team.class)))
                .willThrow(new DataIntegrityViolationException("insert failed"));

        assertThatThrownBy(() -> teamService.create(1L, new TeamCreateRequest("Meety Team", "jay")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_CREATE_FAILED));
    }

    @Test
    @DisplayName("활성 팀이 있으면 hasActiveTeam과 teamId를 반환한다")
    void getMyTeam() {
        User user = User.create();
        Team team = Team.create("Meety Team");
        ReflectionTestUtils.setField(team, "id", 7L);
        TeamMember teamMember = TeamMember.createLeader(user, team, "jay");
        given(teamMemberRepository.findByUserIdAndMembershipStatus(1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.of(teamMember));

        MyTeamResponse response = teamService.getMyTeam(1L);

        assertThat(response.hasActiveTeam()).isTrue();
        assertThat(response.teamId()).isEqualTo(7L);
        then(teamInvitationCodeRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("활성 팀이 없으면 hasActiveTeam이 false인 응답을 반환한다")
    void getMyTeamWithoutActiveTeam() {
        given(teamMemberRepository.findByUserIdAndMembershipStatus(1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.empty());

        MyTeamResponse response = teamService.getMyTeam(1L);

        assertThat(response.hasActiveTeam()).isFalse();
        assertThat(response.teamId()).isNull();
    }

    @Test
    @DisplayName("팀원이 팀을 조회하면 팀 정보와 초대 코드를 반환한다")
    void getTeam() {
        User user = User.create();
        Team team = Team.create("Meety Team");
        ReflectionTestUtils.setField(team, "id", 7L);
        TeamMember teamMember = TeamMember.createLeader(user, team, "jay");
        ReflectionTestUtils.setField(teamMember, "id", 10L);
        given(teamRepository.findByIdAndDeletedAtIsNull(7L)).willReturn(Optional.of(team));
        given(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(7L, 1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.of(teamMember));
        given(teamInvitationCodeRepository.findByTeamIdAndDeletedAtIsNull(7L))
                .willReturn(Optional.of(TeamInvitationCode.create(team, "ABCD1234")));

        TeamDetailResponse response = teamService.getTeam(1L, 7L);

        assertThat(response.teamId()).isEqualTo(7L);
        assertThat(response.teamMemberId()).isEqualTo(10L);
        assertThat(response.name()).isEqualTo("Meety Team");
        assertThat(response.displayName()).isEqualTo("jay");
        assertThat(response.role()).isEqualTo(TeamMemberRole.LEADER);
        assertThat(response.invitationCode()).isEqualTo("ABCD1234");
    }

    @Test
    @DisplayName("존재하지 않거나 삭제된 팀을 조회하면 실패한다")
    void getTeamFailOnUnknownTeam() {
        given(teamRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> teamService.getTeam(1L, 1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_NOT_FOUND));
    }

    @Test
    @DisplayName("활성 팀원이 아니면 팀을 조회할 수 없다")
    void getTeamFailOnNonMember() {
        given(teamRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(Team.create("Meety Team")));
        given(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(1L, 1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> teamService.getTeam(1L, 1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_ACCESS_DENIED));
        then(teamInvitationCodeRepository).shouldHaveNoInteractions();
    }
}
