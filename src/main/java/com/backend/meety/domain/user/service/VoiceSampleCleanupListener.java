package com.backend.meety.domain.user.service;

import com.backend.meety.domain.recording.storage.AudioFileStorage;
import com.backend.meety.domain.user.VoiceProfilePolicy;
import com.backend.meety.domain.user.event.UserWithdrawnEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class VoiceSampleCleanupListener {

    private final AudioFileStorage audioFileStorage;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void deleteVoiceSample(UserWithdrawnEvent event) {
        try {
            audioFileStorage.deleteObject(VoiceProfilePolicy.storageKey(event.userId()));
        } catch (Exception e) {
            log.error("탈퇴 회원의 사전 녹음 음성 삭제에 실패했습니다. userId={}", event.userId(), e);
        }
    }
}
