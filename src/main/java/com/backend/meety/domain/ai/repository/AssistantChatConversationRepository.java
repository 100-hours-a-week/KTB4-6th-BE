package com.backend.meety.domain.ai.repository;

import com.backend.meety.domain.ai.entity.AssistantChatConversation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface AssistantChatConversationRepository extends JpaRepository<AssistantChatConversation, Long> {

    @Modifying(flushAutomatically = true)
    @Query("delete from AssistantChatConversation c where c.team.id = :teamId")
    int deleteAllByTeamId(@Param("teamId") Long teamId);

    @Modifying(flushAutomatically = true)
    @Query("delete from AssistantChatConversation c where c.teamMember.user.id = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select c from AssistantChatConversation c
            where c.team.id = :teamId and c.teamMember.id = :teamMemberId and c.deletedAt is null
            """)
    Optional<AssistantChatConversation> findForUpdate(@Param("teamId") Long teamId,
                                                       @Param("teamMemberId") Long teamMemberId);

    Optional<AssistantChatConversation> findByTeamIdAndTeamMemberIdAndDeletedAtIsNull(Long teamId,
                                                                                       Long teamMemberId);
}
