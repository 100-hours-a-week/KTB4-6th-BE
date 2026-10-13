package com.backend.meety.domain.meeting.repository;

import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.meeting.entity.MeetingSummary;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MeetingSummaryRepository extends JpaRepository<MeetingSummary, Long> {

    interface LatestCompletedSummaryProjection {

        Long getMeetingId();

        Long getSummaryId();
    }

    long countByMeetingId(Long meetingId);

    Optional<MeetingSummary> findByAiRequestId(Long aiRequestId);

    @Query("""
            select ms
            from MeetingSummary ms
            join fetch ms.aiRequest
            where ms.meeting.id = :meetingId
            order by ms.version desc
            limit 1
            """)
    Optional<MeetingSummary> findLatestByMeetingId(@Param("meetingId") Long meetingId);

    @Query("""
            select ms
            from MeetingSummary ms
            join fetch ms.aiRequest r
            where ms.meeting.id = :meetingId
              and r.status = com.backend.meety.domain.ai.entity.AiRequestStatus.COMPLETED
            order by ms.version desc
            limit 1
            """)
    Optional<MeetingSummary> findLatestCompletedByMeetingId(@Param("meetingId") Long meetingId);

    @Query("""
            select count(ms) > 0
            from MeetingSummary ms
            where ms.meeting.id = :meetingId
              and ms.aiRequest.status in :statuses
            """)
    boolean existsByMeetingIdAndAiRequestStatusIn(
            @Param("meetingId") Long meetingId,
            @Param("statuses") Collection<AiRequestStatus> statuses
    );

    @Query("""
            select ms.meeting.id as meetingId,
                   ms.id as summaryId
            from MeetingSummary ms
            join ms.aiRequest r
            where ms.meeting.id in :meetingIds
              and r.status = com.backend.meety.domain.ai.entity.AiRequestStatus.COMPLETED
              and ms.version = (
                  select max(ms2.version)
                  from MeetingSummary ms2
                  join ms2.aiRequest r2
                  where ms2.meeting.id = ms.meeting.id
                    and r2.status = com.backend.meety.domain.ai.entity.AiRequestStatus.COMPLETED
              )
            """)
    List<LatestCompletedSummaryProjection> findLatestCompletedSummaryIdsByMeetingIds(
            @Param("meetingIds") Collection<Long> meetingIds
    );
}
