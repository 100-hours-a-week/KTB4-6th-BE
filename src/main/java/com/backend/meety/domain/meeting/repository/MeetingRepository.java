package com.backend.meety.domain.meeting.repository;

import com.backend.meety.domain.meeting.entity.Meeting;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MeetingRepository extends JpaRepository<Meeting, Long>, MeetingRepositoryCustom {

    @Query("""
            select count(m)
            from Meeting m
            where m.team.id = :teamId
              and m.createdAt >= :startOfDay
              and m.createdAt < :nextDay
            """)
    long countCreatedTodayByTeamId(
            @Param("teamId") Long teamId,
            @Param("startOfDay") LocalDateTime startOfDay,
            @Param("nextDay") LocalDateTime nextDay
    );

    @Query("""
            select m
            from Meeting m
            join fetch m.team
            join fetch m.createdByTeamMember
            where m.id = :meetingId
              and m.deletedAt is null
            """)
    java.util.Optional<Meeting> findDetailByIdAndDeletedAtIsNull(@Param("meetingId") Long meetingId);

    @Query("""
            select m
            from Meeting m
            join fetch m.team
            where m.id = :meetingId
              and m.deletedAt is null
            """)
    Optional<Meeting> findByIdAndDeletedAtIsNull(@Param("meetingId") Long meetingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select m
            from Meeting m
            join fetch m.team
            join fetch m.createdByTeamMember
            where m.id = :meetingId
              and m.deletedAt is null
            """)
    Optional<Meeting> findByIdForUpdateAndDeletedAtIsNull(@Param("meetingId") Long meetingId);
}
