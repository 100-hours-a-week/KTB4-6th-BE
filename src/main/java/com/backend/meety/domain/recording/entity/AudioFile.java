package com.backend.meety.domain.recording.entity;

import com.backend.meety.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "audio_files")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AudioFile extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recording_session_id", nullable = false)
    private RecordingSession recordingSession;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private AudioFileStatus status = AudioFileStatus.UPLOADING;

    @Column(name = "stored_at")
    private LocalDateTime storedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    public static AudioFile create(RecordingSession recordingSession, String storageKey, String contentType) {
        AudioFile audioFile = new AudioFile();
        audioFile.recordingSession = recordingSession;
        audioFile.storageKey = storageKey;
        audioFile.contentType = contentType;
        audioFile.status = AudioFileStatus.UPLOADING;
        return audioFile;
    }

    public boolean isUploading() {
        return status == AudioFileStatus.UPLOADING;
    }

    public boolean isExpired(LocalDateTime now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public void markAvailable(Long fileSizeBytes, Long durationMs, LocalDateTime storedAt, LocalDateTime expiresAt) {
        this.fileSizeBytes = fileSizeBytes;
        this.durationMs = durationMs;
        this.storedAt = storedAt;
        this.expiresAt = expiresAt;
        this.status = AudioFileStatus.AVAILABLE;
    }
}
