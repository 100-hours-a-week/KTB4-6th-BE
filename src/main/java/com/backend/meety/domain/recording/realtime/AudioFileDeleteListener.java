package com.backend.meety.domain.recording.realtime;

import com.backend.meety.domain.recording.event.AudioFileDeleteRequestedEvent;
import com.backend.meety.domain.recording.service.AudioFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class AudioFileDeleteListener {

    private final AudioFileService audioFileService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void deleteAudioObject(AudioFileDeleteRequestedEvent event) {
        audioFileService.completeDelete(event.audioFileId(), event.storageKey());
    }
}
