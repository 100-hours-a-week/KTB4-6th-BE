package com.backend.meety.domain.transcript.repository;

import com.backend.meety.domain.transcript.entity.TranscriptSpeaker;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;

public interface TranscriptSpeakerRepository extends JpaRepository<TranscriptSpeaker, Long> {

    @Query("""
            select ts
            from TranscriptSpeaker ts
            where ts.id = :transcriptSpeakerId
              and ts.meeting.id = :meetingId
              and ts.deletedAt is null
            """)
    Optional<TranscriptSpeaker> findByIdAndMeetingIdAndDeletedAtIsNull(
            @Param("transcriptSpeakerId") Long transcriptSpeakerId,
            @Param("meetingId") Long meetingId
    );

    @Query("""
            select ts from TranscriptSpeaker ts
            left join fetch ts.mappedTeamMember
            where ts.recordingSession.id = :recordingSessionId
              and ts.aiSpeakerId = :speakerId
              and ts.deletedAt is null
            """)
    Optional<TranscriptSpeaker> findAiSpeaker(@Param("recordingSessionId") Long recordingSessionId,
                                               @Param("speakerId") Long speakerId);

    @Query("""
            select ts from TranscriptSpeaker ts
            left join fetch ts.mappedTeamMember
            where ts.recordingSession.id = :recordingSessionId
              and ts.deletedAt is null
            """)
    List<TranscriptSpeaker> findAllAiSpeakers(@Param("recordingSessionId") Long recordingSessionId);

    @Query("""
            select ts from TranscriptSpeaker ts
            left join fetch ts.mappedTeamMember
            where ts.recordingSession.id = :recordingSessionId
              and ts.aiSpeakerId in :speakerIds
              and ts.deletedAt is null
            """)
    List<TranscriptSpeaker> findAiSpeakers(@Param("recordingSessionId") Long recordingSessionId,
                                           @Param("speakerIds") Collection<Long> speakerIds);
}
