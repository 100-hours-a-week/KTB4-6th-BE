package com.backend.meety.domain.meeting.repository;

import com.backend.meety.domain.meeting.entity.MeetingMetric;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MeetingMetricRepository extends JpaRepository<MeetingMetric, Long> {

    boolean existsByAiRequestId(Long aiRequestId);

    @Query("""
            select mm
            from MeetingMetric mm
            join fetch mm.aiRequest r
            join fetch mm.meeting m
            where m.team.id = :teamId
              and m.status = com.backend.meety.domain.meeting.entity.MeetingStatus.COMPLETED
              and m.deletedAt is null
              and m.endedAt >= :start
              and m.endedAt < :endExclusive
              and mm.deletedAt is null
              and r.status = com.backend.meety.domain.ai.entity.AiRequestStatus.COMPLETED
            order by m.id asc, mm.id desc
            """)
    List<MeetingMetric> findDashboardCompletedMetrics(
            @Param("teamId") Long teamId,
            @Param("start") LocalDateTime start,
            @Param("endExclusive") LocalDateTime endExclusive
    );

    @Query(value = """
            select avg(mm.speech_balance_score)
            from meeting_metrics mm
            join meetings m on m.id = mm.meeting_id
            where m.team_id = :teamId
              and m.status = 'COMPLETED'
              and m.deleted_at is null
              and mm.deleted_at is null
              and mm.speech_balance_score is not null
            """, nativeQuery = true)
    BigDecimal averageSpeechBalanceScoreByTeamId(@Param("teamId") Long teamId);
}
