package com.backend.meety.domain.recording.repository;

import com.backend.meety.domain.recording.entity.RecordingSession;
import com.backend.meety.domain.recording.entity.RecordingSessionStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecordingSessionRepository extends JpaRepository<RecordingSession, Long> {

    @Query("""
            select new com.backend.meety.domain.recording.repository.RecordingStatusSnapshot(
                r.id, r.meeting.id, r.status)
            from RecordingSession r
            where r.id in :sessionIds and r.deletedAt is null
            """)
    List<RecordingStatusSnapshot> findStatusSnapshotsByIdIn(@Param("sessionIds") Collection<Long> sessionIds);

    boolean existsByMeetingIdAndStatusInAndDeletedAtIsNull(Long meetingId, Collection<RecordingSessionStatus> statuses);

    Optional<RecordingSession> findByMeetingIdAndStatusInAndDeletedAtIsNull(
            Long meetingId, Collection<RecordingSessionStatus> statuses
    );

    @Query("""
            select r
            from RecordingSession r
            join fetch r.meeting
            join fetch r.startedByTeamMember
            where r.id = :sessionId
              and r.deletedAt is null
            """)
    Optional<RecordingSession> findByIdAndDeletedAtIsNull(@Param("sessionId") Long sessionId);

    @Query("""
            select r
            from RecordingSession r
            join fetch r.meeting m
            join fetch m.team
            join fetch r.startedByTeamMember
            where r.id = :sessionId
              and r.deletedAt is null
            """)
    Optional<RecordingSession> findByIdWithMeetingTeamAndStarter(@Param("sessionId") Long sessionId);

    @Query("select r.meeting.id from RecordingSession r where r.id = :sessionId and r.deletedAt is null")
    Optional<Long> findMeetingIdByIdAndDeletedAtIsNull(@Param("sessionId") Long sessionId);

    @Query("""
            select r
            from RecordingSession r
            join fetch r.meeting m
            where m.team.id = :teamId
              and m.status = com.backend.meety.domain.meeting.entity.MeetingStatus.COMPLETED
              and m.deletedAt is null
              and m.endedAt >= :start
              and m.endedAt < :endExclusive
              and r.status = com.backend.meety.domain.recording.entity.RecordingSessionStatus.COMPLETED
              and r.deletedAt is null
              and r.startedAt is not null
              and r.endedAt is not null
            order by m.id asc, r.id desc
            """)
    List<RecordingSession> findDashboardCompletedSessions(
            @Param("teamId") Long teamId,
            @Param("start") LocalDateTime start,
            @Param("endExclusive") LocalDateTime endExclusive
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RecordingSession r where r.id = :sessionId and r.deletedAt is null")
    Optional<RecordingSession> findByIdForUpdateAndDeletedAtIsNull(@Param("sessionId") Long sessionId);
}
