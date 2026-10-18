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
              and m.id < :beforeMessageId
              and m.deletedAt is null
            order by m.id desc
            """)
    List<AiChatbotMessage> findPageByMeetingId(
            @Param("meetingId") Long meetingId, @Param("beforeMessageId") Long beforeMessageId, Limit limit);

    Optional<AiChatbotMessage> findByAiRequestId(Long aiRequestId);

    boolean existsByMeetingIdAndTeamMemberIdAndDeletedAtIsNull(Long meetingId, Long teamMemberId);

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

    @Query("""
            select m
            from AiChatbotMessage m
            join fetch m.aiRequest
            join fetch m.teamMember
            where m.id = :messageId
            """)
    Optional<AiChatbotMessage> findWithAskerById(@Param("messageId") Long messageId);

    @Query("""
            select m
            from AiChatbotMessage m
            join fetch m.aiRequest r
            join fetch r.team
            join fetch m.meeting
            where r.id = :aiRequestId
            """)
    Optional<AiChatbotMessage> findWithRequestByAiRequestId(@Param("aiRequestId") Long aiRequestId);

    @Query("""
            select m
            from AiChatbotMessage m
            where m.meeting.id = :meetingId
              and m.id < :messageId
              and m.deletedAt is null
            order by m.id asc
            """)
    List<AiChatbotMessage> findPreviousByMeetingId(
            @Param("meetingId") Long meetingId, @Param("messageId") Long messageId);
}
