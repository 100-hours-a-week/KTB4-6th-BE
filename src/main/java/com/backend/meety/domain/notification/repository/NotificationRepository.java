package com.backend.meety.domain.notification.repository;

import com.backend.meety.domain.notification.entity.Notification;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    @Query("""
            select n
            from Notification n
            where n.user.id = :userId
              and n.team.id = :teamId
              and n.deletedAt is null
              and (:cursor is null or n.id < :cursor)
            order by n.id desc
            """)
    List<Notification> findPageByUserIdAndTeamId(
            @Param("userId") Long userId,
            @Param("teamId") Long teamId,
            @Param("cursor") Long cursor,
            Pageable pageable
    );

    @Query("""
            select count(n)
            from Notification n
            where n.user.id = :userId
              and n.team.id = :teamId
              and n.deletedAt is null
              and n.isRead = false
            """)
    long countUnreadByUserIdAndTeamId(
            @Param("userId") Long userId,
            @Param("teamId") Long teamId
    );

    Optional<Notification> findByIdAndUserIdAndTeamIdAndDeletedAtIsNull(
            Long id,
            Long userId,
            Long teamId
    );

    @Query("""
            select max(n.id)
            from Notification n
            where n.user.id = :userId
              and n.team.id = :teamId
              and n.deletedAt is null
            """)
    Long findMaxIdByUserIdAndTeamId(
            @Param("userId") Long userId,
            @Param("teamId") Long teamId
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Notification n
            set n.isRead = true
            where n.user.id = :userId
              and n.team.id = :teamId
              and n.deletedAt is null
              and n.isRead = false
              and n.id <= :maxId
            """)
    int markUnreadAsReadUntilId(
            @Param("userId") Long userId,
            @Param("teamId") Long teamId,
            @Param("maxId") Long maxId
    );
}
