package com.backend.meety.domain.recording.dto;

import com.backend.meety.domain.recording.entity.AudioFile;
import com.backend.meety.domain.recording.entity.AudioFileStatus;

public record AudioFileDeleteResponse(
        Long audioFileId,
        AudioFileStatus status
) {

    public static AudioFileDeleteResponse from(AudioFile audioFile) {
        return new AudioFileDeleteResponse(audioFile.getId(), audioFile.getStatus());
    }
}
