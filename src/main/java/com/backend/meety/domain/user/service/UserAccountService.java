package com.backend.meety.domain.user.service;

import com.backend.meety.domain.user.entity.User;
import com.backend.meety.domain.user.entity.UserAuthAccount;
import com.backend.meety.domain.user.repository.UserAuthAccountRepository;
import com.backend.meety.domain.user.repository.UserRepository;
import com.backend.meety.global.exception.BusinessException;
import com.backend.meety.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserAccountService {

    private final UserRepository userRepository;
    private final UserAuthAccountRepository userAuthAccountRepository;

    @Transactional
    public User findOrCreate(String provider, String providerUserId) {
        return userAuthAccountRepository.findByProviderAndProviderUserId(provider, providerUserId)
                .map(UserAuthAccount::getUser)
                .orElseGet(() -> create(provider, providerUserId));
    }

    @Transactional(readOnly = true)
    public User getByProviderAndProviderUserId(String provider, String providerUserId) {
        return userAuthAccountRepository.findByProviderAndProviderUserId(provider, providerUserId)
                .map(UserAuthAccount::getUser)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR));
    }

    private User create(String provider, String providerUserId) {
        User user = userRepository.save(User.create());
        userAuthAccountRepository.save(UserAuthAccount.of(user, provider, providerUserId));
        return user;
    }
}
