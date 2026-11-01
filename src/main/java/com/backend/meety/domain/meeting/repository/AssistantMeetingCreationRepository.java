package com.backend.meety.domain.meeting.repository;

import com.backend.meety.domain.meeting.entity.AssistantMeetingCreation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssistantMeetingCreationRepository extends JpaRepository<AssistantMeetingCreation, Long> {

    @Query("""
            select c from AssistantMeetingCreation c
            join fetch c.meeting m join fetch m.team join fetch m.createdByTeamMember
            where c.aiRequest.id = :requestId
            """)
    Optional<AssistantMeetingCreation> findByAiRequestIdWithMeeting(@Param("requestId") Long requestId);
}
