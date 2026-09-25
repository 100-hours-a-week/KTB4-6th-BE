package com.backend.meety.domain.transcript.repository;

import com.backend.meety.domain.transcript.entity.TranscriptSpeaker;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
