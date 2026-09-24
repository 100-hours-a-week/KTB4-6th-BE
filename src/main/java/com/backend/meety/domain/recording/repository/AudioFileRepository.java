package com.backend.meety.domain.recording.repository;

import com.backend.meety.domain.recording.entity.AudioFile;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AudioFileRepository extends JpaRepository<AudioFile, Long> {

    boolean existsByRecordingSessionIdAndDeletedAtIsNull(Long recordingSessionId);

    @Query("""
            select a
            from AudioFile a
            join fetch a.recordingSession rs
            join fetch rs.meeting m
            join fetch m.team
            where m.id = :meetingId
              and a.deletedAt is null
            """)
    Optional<AudioFile> findByMeetingIdAndDeletedAtIsNull(@Param("meetingId") Long meetingId);

    @Query("""
            select a
            from AudioFile a
            join fetch a.recordingSession rs
            join fetch rs.meeting m
            join fetch m.team
            where a.id = :audioFileId
              and a.deletedAt is null
            """)
    Optional<AudioFile> findByIdAndDeletedAtIsNull(@Param("audioFileId") Long audioFileId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AudioFile a where a.id = :audioFileId and a.deletedAt is null")
    Optional<AudioFile> findByIdForUpdateAndDeletedAtIsNull(@Param("audioFileId") Long audioFileId);
}
