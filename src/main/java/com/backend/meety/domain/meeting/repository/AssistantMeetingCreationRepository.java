package com.backend.meety.domain.meeting.repository;

import com.backend.meety.domain.meeting.entity.AssistantMeetingCreation;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssistantMeetingCreationRepository extends JpaRepository<AssistantMeetingCreation, Long> {

    interface CreatedMeetingProjection {
        Long getAiRequestId();
        Long getMeetingId();
    }

    // Keep the original ID after meeting soft deletion: null means never created.
    @Query("""
            select c.aiRequest.id as aiRequestId, c.meeting.id as meetingId
            from AssistantMeetingCreation c
            where c.aiRequest.id in :requestIds and c.deletedAt is null
            """)
    List<CreatedMeetingProjection> findCreatedMeetingIds(@Param("requestIds") Collection<Long> requestIds);

    @Query("""
            select c from AssistantMeetingCreation c
            join fetch c.meeting m join fetch m.team join fetch m.createdByTeamMember
            where c.aiRequest.id = :requestId
            """)
    Optional<AssistantMeetingCreation> findByAiRequestIdWithMeeting(@Param("requestId") Long requestId);
}
