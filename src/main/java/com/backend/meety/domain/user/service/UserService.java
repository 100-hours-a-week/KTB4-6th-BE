package com.backend.meety.domain.user.service;

import com.backend.meety.domain.auth.client.OAuthProviderClient;
import com.backend.meety.domain.user.entity.User;
import com.backend.meety.domain.user.entity.UserAuthAccount;
import com.backend.meety.domain.user.exception.UserErrorCode;
import com.backend.meety.domain.user.exception.UserException;
import com.backend.meety.domain.user.repository.UserAuthAccountRepository;
import com.backend.meety.domain.user.repository.UserRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserAuthAccountRepository userAuthAccountRepository;
    private final UserAccountService userAccountService;
    private final Map<String, OAuthProviderClient> oAuthProviderClients;

    public void withdraw(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserException(UserErrorCode.USER_DELETE_FAILED));
        if (user.isWithdrawn()) {
            return;
        }
        // TODO: 팀 도메인 구현 후 팀장 위임/팀 삭제 선행 검증(409)과 team_members 소속 종료 처리 추가
        unlinkProviders(userId);
        userAccountService.withdraw(userId);
    }

    private void unlinkProviders(Long userId) {
        for (UserAuthAccount account : userAuthAccountRepository.findAllByUserId(userId)) {
            OAuthProviderClient client = oAuthProviderClients.get(account.getProvider());
            if (client == null || !client.unlink(account.getProviderUserId())) {
                throw new UserException(UserErrorCode.USER_DELETE_FAILED);
            }
        }
    }
}
