package com.backend.meety.domain.user.service;

import com.backend.meety.domain.auth.client.OAuthProviderClient;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.exception.TeamErrorCode;
import com.backend.meety.domain.team.exception.TeamException;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
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
    private final TeamMemberRepository teamMemberRepository;
    private final Map<String, OAuthProviderClient> oAuthProviderClients;

    public void withdraw(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserException(UserErrorCode.USER_DELETE_FAILED));
        if (user.isWithdrawn()) {
            return;
        }
        validateNotTeamLeader(userId);
        unlinkProviders(userId);
        userAccountService.withdraw(userId);
    }

    private void validateNotTeamLeader(Long userId) {
        teamMemberRepository.findByUserIdAndMembershipStatus(userId, MembershipStatus.ACTIVE)
                .filter(TeamMember::isLeader)
                .ifPresent(ignored -> {
                    throw new TeamException(TeamErrorCode.LEADER_MUST_TRANSFER_OR_DELETE_TEAM);
                });
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
