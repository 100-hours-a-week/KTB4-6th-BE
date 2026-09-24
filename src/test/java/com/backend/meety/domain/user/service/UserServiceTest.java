package com.backend.meety.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.backend.meety.domain.auth.client.OAuthProviderClient;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.exception.TeamErrorCode;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.domain.user.entity.User;
import com.backend.meety.domain.user.entity.UserAuthAccount;
import com.backend.meety.domain.user.exception.UserErrorCode;
import com.backend.meety.domain.user.repository.UserAuthAccountRepository;
import com.backend.meety.domain.user.repository.UserRepository;
import com.backend.meety.global.exception.BusinessException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserAuthAccountRepository userAuthAccountRepository;

    @Mock
    private UserAccountService userAccountService;

    @Mock
    private TeamMemberRepository teamMemberRepository;

    @Mock
    private OAuthProviderClient kakaoOAuthClient;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, userAuthAccountRepository,
                userAccountService, teamMemberRepository, Map.of("kakao", kakaoOAuthClient));
    }

    @Test
    @DisplayName("탈퇴하면 provider 연결을 끊고 계정을 논리삭제한다")
    void withdraw() {
        User user = User.create();
        given(userRepository.findById(1L)).willReturn(Optional.of(user));
        given(userAuthAccountRepository.findAllByUserId(1L))
                .willReturn(List.of(UserAuthAccount.of(user, "kakao", "12345")));
        given(kakaoOAuthClient.unlink("12345")).willReturn(true);

        userService.withdraw(1L);

        then(kakaoOAuthClient).should().unlink("12345");
        then(userAccountService).should().withdraw(1L);
    }

    @Test
    @DisplayName("이미 탈퇴한 사용자는 아무 처리 없이 성공한다")
    void withdrawIsIdempotent() {
        User user = User.create();
        user.withdraw(LocalDateTime.now());
        given(userRepository.findById(1L)).willReturn(Optional.of(user));

        userService.withdraw(1L);

        then(kakaoOAuthClient).shouldHaveNoInteractions();
        then(userAccountService).should(never()).withdraw(1L);
    }

    @Test
    @DisplayName("unlink에 실패하면 탈퇴를 중단하고 DB를 변경하지 않는다")
    void abortOnUnlinkFailure() {
        User user = User.create();
        given(userRepository.findById(1L)).willReturn(Optional.of(user));
        given(userAuthAccountRepository.findAllByUserId(1L))
                .willReturn(List.of(UserAuthAccount.of(user, "kakao", "12345")));
        given(kakaoOAuthClient.unlink("12345")).willReturn(false);

        assertThatThrownBy(() -> userService.withdraw(1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(UserErrorCode.USER_DELETE_FAILED));
        then(userAccountService).should(never()).withdraw(1L);
    }

    @Test
    @DisplayName("사용자가 존재하지 않으면 탈퇴에 실패한다")
    void failOnUnknownUser() {
        given(userRepository.findById(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userService.withdraw(1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(UserErrorCode.USER_DELETE_FAILED));
    }

    @Test
    @DisplayName("연결 해제 클라이언트가 없는 provider 계정이면 탈퇴에 실패한다")
    void failOnUnknownProvider() {
        User user = User.create();
        given(userRepository.findById(1L)).willReturn(Optional.of(user));
        given(userAuthAccountRepository.findAllByUserId(1L))
                .willReturn(List.of(UserAuthAccount.of(user, "naver", "999")));

        assertThatThrownBy(() -> userService.withdraw(1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(UserErrorCode.USER_DELETE_FAILED));
        then(userAccountService).should(never()).withdraw(1L);
    }

    @Test
    @DisplayName("팀장 상태로 탈퇴하면 409를 반환하고 unlink를 호출하지 않는다")
    void withdrawRejectedWhenTeamLeader() {
        User user = User.create();
        Team team = Team.create("팀");
        given(userRepository.findById(1L)).willReturn(Optional.of(user));
        given(teamMemberRepository.findByUserIdAndMembershipStatus(1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.of(TeamMember.createLeader(user, team, "팀장")));

        assertThatThrownBy(() -> userService.withdraw(1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(TeamErrorCode.LEADER_MUST_TRANSFER_OR_DELETE_TEAM);

        then(kakaoOAuthClient).should(never()).unlink(org.mockito.ArgumentMatchers.anyString());
        then(userAccountService).should(never()).withdraw(1L);
    }

    @Test
    @DisplayName("팀장이 아닌 팀원은 탈퇴할 수 있다")
    void withdrawAllowedForMember() {
        User user = User.create();
        Team team = Team.create("팀");
        given(userRepository.findById(1L)).willReturn(Optional.of(user));
        given(teamMemberRepository.findByUserIdAndMembershipStatus(1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.of(TeamMember.createMember(user, team, "팀원")));
        given(userAuthAccountRepository.findAllByUserId(1L))
                .willReturn(List.of(UserAuthAccount.of(user, "kakao", "12345")));
        given(kakaoOAuthClient.unlink("12345")).willReturn(true);

        userService.withdraw(1L);

        then(userAccountService).should().withdraw(1L);
    }

    @Test
    @DisplayName("소속 팀이 없어도 탈퇴할 수 있다")
    void withdrawAllowedWithoutTeam() {
        User user = User.create();
        given(userRepository.findById(1L)).willReturn(Optional.of(user));
        given(teamMemberRepository.findByUserIdAndMembershipStatus(1L, MembershipStatus.ACTIVE))
                .willReturn(Optional.empty());
        given(userAuthAccountRepository.findAllByUserId(1L)).willReturn(List.of());

        userService.withdraw(1L);

        then(userAccountService).should().withdraw(1L);
    }
}
