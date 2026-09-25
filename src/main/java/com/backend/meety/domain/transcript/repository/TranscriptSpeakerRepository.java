package com.backend.meety.domain.transcript.repository;

import com.backend.meety.domain.transcript.entity.TranscriptSpeaker;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TranscriptSpeakerRepository extends JpaRepository<TranscriptSpeaker, Long> {

    @Query("""
            select ts
            from TranscriptSpeaker ts
            where ts.meeting.id = :meetingId
              and ts.deletedAt is null
            order by ts.id asc
            """)
    List<TranscriptSpeaker> findAllByMeetingIdOrderById(
            @Param("meetingId") Long meetingId
    );
}
