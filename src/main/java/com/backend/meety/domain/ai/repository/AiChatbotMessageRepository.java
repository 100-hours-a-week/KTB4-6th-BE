package com.backend.meety.domain.ai.repository;

import com.backend.meety.domain.ai.entity.AiChatbotMessage;
import com.backend.meety.domain.ai.entity.AiRequestStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiChatbotMessageRepository extends JpaRepository<AiChatbotMessage, Long> {

    @Query("""
            select m
            from AiChatbotMessage m
            join fetch m.aiRequest
            join fetch m.teamMember
            where m.meeting.id = :meetingId
              and m.id > :afterMessageId
              and m.deletedAt is null
            order by m.id asc
            """)
    List<AiChatbotMessage> findPageByMeetingId(
            @Param("meetingId") Long meetingId, @Param("afterMessageId") Long afterMessageId, Limit limit);

    Optional<AiChatbotMessage> findByAiRequestId(Long aiRequestId);

    @Query("""
            select m
            from AiChatbotMessage m
            join fetch m.aiRequest r
            where m.meeting.id = :meetingId
              and r.status in :statuses
              and m.deletedAt is null
            """)
    List<AiChatbotMessage> findByMeetingIdAndAiRequestStatusIn(
            @Param("meetingId") Long meetingId, @Param("statuses") Collection<AiRequestStatus> statuses);
}
