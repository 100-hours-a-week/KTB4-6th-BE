package com.backend.meety.domain.transcript.repository;

import com.backend.meety.domain.transcript.entity.TranscriptSegment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TranscriptSegmentRepository extends JpaRepository<TranscriptSegment, Long> {

    boolean existsBySourceSegmentKey(String sourceSegmentKey);

    @Query("""
            select s
            from TranscriptSegment s
            left join fetch s.transcriptSpeaker speaker
            left join fetch speaker.mappedTeamMember
            where s.meeting.id = :meetingId
              and s.deletedAt is null
            order by s.sequenceNumber asc, s.id asc
            """)
    List<TranscriptSegment> findAllByMeetingIdOrderBySequence(
            @Param("meetingId") Long meetingId
    );

    @Query("""
            select s
            from TranscriptSegment s
            left join fetch s.transcriptSpeaker speaker
            left join fetch speaker.mappedTeamMember
            where s.meeting.id = :meetingId
              and s.deletedAt is null
              and lower(s.content) like concat('%', lower(:keyword), '%')
            order by s.sequenceNumber asc, s.id asc
            """)
    List<TranscriptSegment> searchByMeetingIdAndContent(
            @Param("meetingId") Long meetingId,
            @Param("keyword") String keyword
    );
}
