package com.backend.meety.domain.ai.repository;

import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.ai.entity.AssistantChatTurn;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssistantChatTurnRepository extends JpaRepository<AssistantChatTurn, Long> {

    @Modifying(flushAutomatically = true)
    @Query("delete from AssistantChatTurn t where t.conversation.id in "
            + "(select c.id from AssistantChatConversation c where c.team.id = :teamId)")
    int deleteAllByTeamId(@Param("teamId") Long teamId);

    @Modifying(flushAutomatically = true)
    @Query("delete from AssistantChatTurn t where t.conversation.id in "
            + "(select c.id from AssistantChatConversation c where c.teamMember.user.id = :userId)")
    int deleteAllByUserId(@Param("userId") Long userId);

    Optional<AssistantChatTurn> findByAiRequestId(Long aiRequestId);

    @Query("""
            select t from AssistantChatTurn t join fetch t.aiRequest r
            where t.conversation.id = :conversationId and t.id < :cursor and t.deletedAt is null
            order by t.id desc
            """)
    List<AssistantChatTurn> findPage(@Param("conversationId") Long conversationId,
                                     @Param("cursor") Long cursor, Limit limit);

    @Query("""
            select t from AssistantChatTurn t join fetch t.aiRequest r
            where t.conversation.id = :conversationId and r.status in :statuses
              and t.deletedAt is null and r.deletedAt is null
            """)
    List<AssistantChatTurn> findProcessing(@Param("conversationId") Long conversationId,
                                           @Param("statuses") Collection<AiRequestStatus> statuses);

    @Query("""
            select t from AssistantChatTurn t join fetch t.aiRequest r
            join fetch r.teamMember requestMember
            join fetch t.conversation c join fetch c.team join fetch c.teamMember tm
            where r.id = :requestId and t.deletedAt is null
            """)
    Optional<AssistantChatTurn> findWithContextByRequestId(@Param("requestId") Long requestId);

    @Query("""
            select t from AssistantChatTurn t join fetch t.aiRequest r
            join fetch t.conversation c join fetch c.teamMember tm
            where r.id = :requestId and tm.user.id = :userId
              and tm.membershipStatus = com.backend.meety.domain.team.entity.MembershipStatus.ACTIVE
              and tm.deletedAt is null and c.deletedAt is null and t.deletedAt is null
            """)
    Optional<AssistantChatTurn> findOwnedRequest(@Param("requestId") Long requestId,
                                                 @Param("userId") Long userId);

    @Query("""
            select t from AssistantChatTurn t join fetch t.aiRequest r
            join fetch t.conversation c join fetch c.teamMember tm join fetch c.team
            where r.id = :requestId and c.team.id = :teamId and tm.user.id = :userId
              and r.requestType = com.backend.meety.domain.ai.entity.AiRequestType.GLOBAL_CHAT
              and r.status = com.backend.meety.domain.ai.entity.AiRequestStatus.COMPLETED
              and tm.membershipStatus = com.backend.meety.domain.team.entity.MembershipStatus.ACTIVE
              and tm.deletedAt is null and c.deletedAt is null and t.deletedAt is null
              and t.meetingDraft is not null
            """)
    Optional<AssistantChatTurn> findCompletedDraftForConfirmation(@Param("requestId") Long requestId,
                                                                  @Param("teamId") Long teamId,
                                                                  @Param("userId") Long userId);

    @Query("""
            select t from AssistantChatTurn t join fetch t.aiRequest r
            where t.conversation.id = :conversationId and t.id < :beforeId and t.deletedAt is null
            order by t.id desc
            """)
    List<AssistantChatTurn> findRecentForContext(@Param("conversationId") Long conversationId,
                                                  @Param("beforeId") Long beforeId, Limit limit);

    @Query("""
            select t from AssistantChatTurn t join fetch t.aiRequest r
            where r.requestType = com.backend.meety.domain.ai.entity.AiRequestType.GLOBAL_CHAT
              and r.status = com.backend.meety.domain.ai.entity.AiRequestStatus.PROCESSING
              and r.updatedAt <= :staleBefore and t.deletedAt is null
            order by r.id asc
            """)
    List<AssistantChatTurn> findStaleProcessing(@Param("staleBefore") LocalDateTime staleBefore, Limit limit);
}
