package com.backend.meety.domain.user.service;

import com.backend.meety.domain.auth.exception.AuthErrorCode;
import com.backend.meety.domain.auth.exception.AuthException;
import com.backend.meety.domain.user.entity.User;
import com.backend.meety.domain.user.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserVoiceProfileSaveService {

    private final UserRepository userRepository;

    @Transactional
    public void save(Long userId, String storageKey, List<Double> embedding, String embeddingVersion) {
        User user = userRepository.findById(userId)
                .filter(found -> !found.isWithdrawn())
                .orElseThrow(() -> new AuthException(AuthErrorCode.AUTHENTICATION_REQUIRED));
        user.registerVoiceProfile(storageKey, embedding, embeddingVersion);
    }
}
