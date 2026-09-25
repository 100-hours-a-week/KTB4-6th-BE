package com.backend.meety.domain.meeting.repository;

import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.meeting.entity.MeetingSummary;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MeetingSummaryRepository extends JpaRepository<MeetingSummary, Long> {

    long countByMeetingId(Long meetingId);

    Optional<MeetingSummary> findByAiRequestId(Long aiRequestId);

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
}
