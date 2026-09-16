package com.backend.meety.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.backend.meety.domain.auth.service.RefreshTokenService;
import com.backend.meety.domain.user.entity.User;
import com.backend.meety.domain.user.entity.UserAuthAccount;
import com.backend.meety.domain.user.repository.UserAuthAccountRepository;
import com.backend.meety.domain.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserAccountServiceTest {

    private static final String PROVIDER = "kakao";
    private static final String PROVIDER_USER_ID = "12345";

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserAuthAccountRepository userAuthAccountRepository;

    @Mock
    private RefreshTokenService refreshTokenService;

    private UserAccountService userAccountService;

    @BeforeEach
    void setUp() {
        userAccountService = new UserAccountService(
                userRepository, userAuthAccountRepository, refreshTokenService, Clock.systemDefaultZone());
    }

    @Test
    @DisplayName("기존 활성 계정이면 그대로 사용자를 반환한다")
    void findExistingUser() {
        User user = User.create();
        UserAuthAccount account = UserAuthAccount.of(user, PROVIDER, PROVIDER_USER_ID);
        given(userAuthAccountRepository.findByProviderAndProviderUserId(PROVIDER, PROVIDER_USER_ID))
                .willReturn(Optional.of(account));

        User result = userAccountService.findOrCreate(PROVIDER, PROVIDER_USER_ID);

        assertThat(result).isSameAs(user);
        then(userRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("탈퇴한 계정으로 재로그인하면 계정을 복구한다")
    void restoreWithdrawnAccount() {
        User user = User.create();
        UserAuthAccount account = UserAuthAccount.of(user, PROVIDER, PROVIDER_USER_ID);
        LocalDateTime withdrawnAt = LocalDateTime.now().minusDays(1);
        user.withdraw(withdrawnAt);
        account.withdraw(withdrawnAt);
        given(userAuthAccountRepository.findByProviderAndProviderUserId(PROVIDER, PROVIDER_USER_ID))
                .willReturn(Optional.of(account));

        User result = userAccountService.findOrCreate(PROVIDER, PROVIDER_USER_ID);

        assertThat(result.isWithdrawn()).isFalse();
        assertThat(account.isWithdrawn()).isFalse();
    }

    @Test
    @DisplayName("계정이 없으면 사용자와 인증 계정을 새로 생성한다")
    void createNewUser() {
        User user = User.create();
        given(userAuthAccountRepository.findByProviderAndProviderUserId(PROVIDER, PROVIDER_USER_ID))
                .willReturn(Optional.empty());
        given(userRepository.save(any(User.class))).willReturn(user);

        User result = userAccountService.findOrCreate(PROVIDER, PROVIDER_USER_ID);

        assertThat(result).isSameAs(user);
        then(userAuthAccountRepository).should().save(any(UserAuthAccount.class));
    }

    @Test
    @DisplayName("탈퇴 처리 시 사용자·인증 계정을 논리삭제하고 RT를 전량 폐기한다")
    void withdraw() {
        User user = User.create();
        UserAuthAccount account = UserAuthAccount.of(user, PROVIDER, PROVIDER_USER_ID);
        given(userRepository.findById(1L)).willReturn(Optional.of(user));
        given(userAuthAccountRepository.findAllByUserId(1L)).willReturn(List.of(account));

        userAccountService.withdraw(1L);

        assertThat(user.isWithdrawn()).isTrue();
        assertThat(account.isWithdrawn()).isTrue();
        then(refreshTokenService).should().revokeAll(1L);
    }

    @Test
    @DisplayName("이미 탈퇴 처리된 사용자는 다시 처리하지 않는다")
    void withdrawIsIdempotent() {
        User user = User.create();
        user.withdraw(LocalDateTime.now());
        given(userRepository.findById(1L)).willReturn(Optional.of(user));

        userAccountService.withdraw(1L);

        then(refreshTokenService).shouldHaveNoInteractions();
    }
}
