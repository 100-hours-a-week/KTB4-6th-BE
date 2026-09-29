package com.backend.meety.domain.auth.service;

import com.backend.meety.domain.auth.dto.LoginResponse;
import com.backend.meety.domain.auth.exception.AuthErrorCode;
import com.backend.meety.domain.auth.exception.AuthException;
import com.backend.meety.domain.user.entity.User;
import com.backend.meety.domain.user.entity.UserAuthAccount;
import com.backend.meety.domain.user.service.UserAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LocalAuthService {

    static final String PROVIDER = "local";

    private final UserAccountService userAccountService;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;

    @Transactional
    public LoginResponse signup(String loginId, String password) {
        if (userAccountService.findAccount(PROVIDER, loginId).isPresent()) {
            throw new AuthException(AuthErrorCode.DUPLICATE_LOGIN_ID);
        }
        User user;
        try {
            user = userAccountService.createLocal(PROVIDER, loginId, passwordEncoder.encode(password));
        } catch (DataIntegrityViolationException e) {
            throw new AuthException(AuthErrorCode.DUPLICATE_LOGIN_ID);
        }
        return authService.issueTokens(user);
    }

    @Transactional
    public LoginResponse login(String loginId, String password) {
        UserAuthAccount account = userAccountService.findAccount(PROVIDER, loginId)
                .filter(found -> !found.isWithdrawn() && !found.getUser().isWithdrawn())
                .filter(found -> passwordEncoder.matches(password, found.getPasswordHash()))
                .orElseThrow(() -> new AuthException(AuthErrorCode.INVALID_CREDENTIALS));
        return authService.issueTokens(account.getUser());
    }
}
