package com.backend.meety.domain.recording.repository;

import com.backend.meety.domain.recording.entity.RecordingSession;
import com.backend.meety.domain.recording.entity.RecordingSessionStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecordingSessionRepository extends JpaRepository<RecordingSession, Long> {

    boolean existsByMeetingIdAndStatusInAndDeletedAtIsNull(Long meetingId, Collection<RecordingSessionStatus> statuses);

    Optional<RecordingSession> findByMeetingIdAndStatusInAndDeletedAtIsNull(
            Long meetingId, Collection<RecordingSessionStatus> statuses
    );

    @Query("select r.meeting.id from RecordingSession r where r.id = :sessionId and r.deletedAt is null")
    Optional<Long> findMeetingIdByIdAndDeletedAtIsNull(@Param("sessionId") Long sessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RecordingSession r where r.id = :sessionId and r.deletedAt is null")
    Optional<RecordingSession> findByIdForUpdateAndDeletedAtIsNull(@Param("sessionId") Long sessionId);
}
