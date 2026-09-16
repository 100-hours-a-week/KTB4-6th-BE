package com.backend.meety.domain.user.service;

import com.backend.meety.domain.auth.service.RefreshTokenService;
import com.backend.meety.domain.user.entity.User;
import com.backend.meety.domain.user.entity.UserAuthAccount;
import com.backend.meety.domain.user.exception.UserErrorCode;
import com.backend.meety.domain.user.exception.UserException;
import com.backend.meety.domain.user.repository.UserAuthAccountRepository;
import com.backend.meety.domain.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserAccountService {

    private final UserRepository userRepository;
    private final UserAuthAccountRepository userAuthAccountRepository;
    private final RefreshTokenService refreshTokenService;
    private final Clock clock;

    @Transactional
    public User findOrCreate(String provider, String providerUserId) {
        return userAuthAccountRepository.findByProviderAndProviderUserId(provider, providerUserId)
                .map(this::restoreIfWithdrawn)
                .orElseGet(() -> create(provider, providerUserId));
    }

    @Transactional
    public void withdraw(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserException(UserErrorCode.USER_DELETE_FAILED));
        if (user.isWithdrawn()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        user.withdraw(now);
        userAuthAccountRepository.findAllByUserId(userId)
                .forEach(account -> account.withdraw(now));
        refreshTokenService.revokeAll(userId);
    }

    private User create(String provider, String providerUserId) {
        User user = userRepository.save(User.create());
        userAuthAccountRepository.save(UserAuthAccount.of(user, provider, providerUserId));
        return user;
    }

    private User restoreIfWithdrawn(UserAuthAccount account) {
        User user = account.getUser();
        if (account.isWithdrawn()) {
            account.reactivate();
        }
        if (user.isWithdrawn()) {
            user.reactivate();
        }
        return user;
    }
}
