package com.backend.meety.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.backend.meety.domain.auth.dto.LoginResponse;
import com.backend.meety.domain.auth.exception.AuthErrorCode;
import com.backend.meety.domain.user.entity.User;
import com.backend.meety.domain.user.entity.UserAuthAccount;
import com.backend.meety.domain.user.service.UserAccountService;
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
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class LocalAuthServiceTest {

    private static final String LOGIN_ID = "loadtest-0001";
    private static final String PASSWORD = "secret";
    private static final String HASH = "$2a$hash";
    private static final LoginResponse TOKENS = new LoginResponse(1L, "access", "refresh", 1800L, 1209600L);

    @Mock
    private UserAccountService userAccountService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuthService authService;

    @InjectMocks
    private LocalAuthService localAuthService;

    private User user;
    private UserAuthAccount account;

    @BeforeEach
    void setUp() {
        user = User.create();
        account = UserAuthAccount.createLocal(user, "local", LOGIN_ID, HASH);
    }

    @Test
    @DisplayName("새 아이디면 계정을 만들고 토큰을 발급한다")
    void signup() {
        given(userAccountService.findAccount("local", LOGIN_ID)).willReturn(Optional.empty());
        given(passwordEncoder.encode(PASSWORD)).willReturn(HASH);
        given(userAccountService.createLocal("local", LOGIN_ID, HASH)).willReturn(user);
        given(authService.issueTokens(user)).willReturn(TOKENS);

        assertThat(localAuthService.signup(LOGIN_ID, PASSWORD)).isSameAs(TOKENS);
    }

    @Test
    @DisplayName("이미 사용 중인 아이디면 가입할 수 없다")
    void signupFailOnDuplicateLoginId() {
        given(userAccountService.findAccount("local", LOGIN_ID)).willReturn(Optional.of(account));

        assertThatThrownBy(() -> localAuthService.signup(LOGIN_ID, PASSWORD))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.DUPLICATE_LOGIN_ID));
        then(userAccountService).should(never()).createLocal(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("탈퇴한 아이디도 다시 가입할 수 없다")
    void signupFailOnWithdrawnLoginId() {
        account.withdraw(LocalDateTime.of(2026, 9, 1, 0, 0));
        given(userAccountService.findAccount("local", LOGIN_ID)).willReturn(Optional.of(account));

        assertThatThrownBy(() -> localAuthService.signup(LOGIN_ID, PASSWORD))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.DUPLICATE_LOGIN_ID));
    }

    @Test
    @DisplayName("동시 가입으로 UNIQUE에 걸리면 중복 아이디로 응답한다")
    void signupFailOnUniqueViolation() {
        given(userAccountService.findAccount("local", LOGIN_ID)).willReturn(Optional.empty());
        given(passwordEncoder.encode(PASSWORD)).willReturn(HASH);
        given(userAccountService.createLocal("local", LOGIN_ID, HASH))
                .willThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> localAuthService.signup(LOGIN_ID, PASSWORD))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.DUPLICATE_LOGIN_ID));
        then(authService).should(never()).issueTokens(any());
    }

    @Test
    @DisplayName("아이디와 비밀번호가 맞으면 토큰을 발급한다")
    void login() {
        given(userAccountService.findAccount("local", LOGIN_ID)).willReturn(Optional.of(account));
        given(passwordEncoder.matches(PASSWORD, HASH)).willReturn(true);
        given(authService.issueTokens(user)).willReturn(TOKENS);

        assertThat(localAuthService.login(LOGIN_ID, PASSWORD)).isSameAs(TOKENS);
    }

    @Test
    @DisplayName("비밀번호가 틀리면 로그인할 수 없다")
    void loginFailOnWrongPassword() {
        given(userAccountService.findAccount("local", LOGIN_ID)).willReturn(Optional.of(account));
        given(passwordEncoder.matches(PASSWORD, HASH)).willReturn(false);

        assertThatThrownBy(() -> localAuthService.login(LOGIN_ID, PASSWORD))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS));
        then(authService).should(never()).issueTokens(any());
    }

    @Test
    @DisplayName("없는 아이디면 비밀번호 불일치와 같은 오류를 돌려준다")
    void loginFailOnUnknownLoginId() {
        given(userAccountService.findAccount("local", LOGIN_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> localAuthService.login(LOGIN_ID, PASSWORD))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS));
        then(passwordEncoder).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("탈퇴한 계정은 로그인할 수 없다")
    void loginFailOnWithdrawnAccount() {
        account.withdraw(LocalDateTime.of(2026, 9, 1, 0, 0));
        given(userAccountService.findAccount("local", LOGIN_ID)).willReturn(Optional.of(account));

        assertThatThrownBy(() -> localAuthService.login(LOGIN_ID, PASSWORD))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS));
        then(passwordEncoder).shouldHaveNoInteractions();
    }
}
