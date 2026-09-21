package com.backend.meety.domain.meeting.repository;

import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MeetingRepository extends JpaRepository<Meeting, Long>, MeetingRepositoryCustom {

    interface CompletedMeetingSummaryAggregate {

        Long getTotalMeetingCount();

        Long getTotalMeetingMinutes();
    }

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

    @Query("""
            select m
            from Meeting m
            where m.team.id = :teamId
              and m.deletedAt is null
              and case
                    when m.status = com.backend.meety.domain.meeting.entity.MeetingStatus.WAITING
                    then m.scheduledAt
                    else m.startedAt
                  end >= :start
              and case
                    when m.status = com.backend.meety.domain.meeting.entity.MeetingStatus.WAITING
                    then m.scheduledAt
                    else m.startedAt
                  end < :endExclusive
            order by
              case
                when m.status = com.backend.meety.domain.meeting.entity.MeetingStatus.WAITING
                then m.scheduledAt
                else m.startedAt
              end asc,
              m.id asc
            """)
    List<Meeting> findCalendarMeetingsByTeamIdAndEffectiveStartAtBetween(
            @Param("teamId") Long teamId,
            @Param("start") LocalDateTime start,
            @Param("endExclusive") LocalDateTime endExclusive
    );

    @Query(value = """
            select
              count(*) as totalMeetingCount,
              coalesce(sum(
                case
                  when started_at is not null and ended_at is not null
                  then timestampdiff(minute, started_at, ended_at)
                  else 0
                end
              ), 0) as totalMeetingMinutes
            from meetings
            where team_id = :teamId
              and status = 'COMPLETED'
              and deleted_at is null
            """, nativeQuery = true)
    CompletedMeetingSummaryAggregate aggregateCompletedMeetingSummary(@Param("teamId") Long teamId);

    @Query("""
            select m
            from Meeting m
            where m.team.id = :teamId
              and m.deletedAt is null
              and case
                    when m.status = com.backend.meety.domain.meeting.entity.MeetingStatus.WAITING
                    then m.scheduledAt
                    else m.startedAt
                  end >= :start
              and case
                    when m.status = com.backend.meety.domain.meeting.entity.MeetingStatus.WAITING
                    then m.scheduledAt
                    else m.startedAt
                  end < :endExclusive
            order by
              case
                when m.status = com.backend.meety.domain.meeting.entity.MeetingStatus.WAITING
                then m.scheduledAt
                else m.startedAt
              end asc,
              m.id asc
            """)
    List<Meeting> findTodayMeetingsByTeamIdAndEffectiveStartAtBetween(
            @Param("teamId") Long teamId,
            @Param("start") LocalDateTime start,
            @Param("endExclusive") LocalDateTime endExclusive
    );

    boolean existsByTeamIdAndStatusAndDeletedAtIsNull(Long teamId, MeetingStatus status);
}
