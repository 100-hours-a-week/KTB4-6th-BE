package com.backend.meety.domain.recording.repository;

import com.backend.meety.domain.recording.entity.AudioFile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AudioFileRepository extends JpaRepository<AudioFile, Long> {

    boolean existsByRecordingSessionIdAndDeletedAtIsNull(Long recordingSessionId);
}
