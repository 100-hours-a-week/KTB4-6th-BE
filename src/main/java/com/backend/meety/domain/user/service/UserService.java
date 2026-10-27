package com.backend.meety.domain.user.service;

import com.backend.meety.domain.auth.client.OAuthProviderClient;
import com.backend.meety.domain.recording.storage.AudioFileStorage;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.exception.TeamErrorCode;
import com.backend.meety.domain.team.exception.TeamException;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.domain.user.VoiceProfilePolicy;
import com.backend.meety.domain.user.entity.User;
import com.backend.meety.domain.user.entity.UserAuthAccount;
import com.backend.meety.domain.user.exception.UserErrorCode;
import com.backend.meety.domain.user.exception.UserException;
import com.backend.meety.domain.user.repository.UserAuthAccountRepository;
import com.backend.meety.domain.user.repository.UserRepository;
import com.backend.meety.global.security.AccessTokenBlacklist;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserAuthAccountRepository userAuthAccountRepository;
    private final UserAccountService userAccountService;
    private final TeamMemberRepository teamMemberRepository;
    private final Map<String, OAuthProviderClient> oAuthProviderClients;
    private final AccessTokenBlacklist accessTokenBlacklist;
    private final AudioFileStorage audioFileStorage;

    public void withdraw(Long userId, String accessToken) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserException(UserErrorCode.USER_DELETE_FAILED));
        if (user.isWithdrawn()) {
            return;
        }
        validateNotTeamLeader(userId);
        unlinkProviders(userId);
        userAccountService.withdraw(userId);
        accessTokenBlacklist.register(accessToken);
        deleteVoiceSample(userId);
    }

    private void deleteVoiceSample(Long userId) {
        try {
            audioFileStorage.deleteObject(VoiceProfilePolicy.storageKey(userId));
        } catch (Exception e) {
            log.error("탈퇴 회원의 사전 녹음 음성 삭제에 실패했습니다. userId={}", userId, e);
        }
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
