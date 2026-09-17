package com.backend.meety.domain.team.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.backend.meety.domain.team.dto.TeamCreateRequest;
import com.backend.meety.domain.team.dto.TeamCreateResponse;
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
}
