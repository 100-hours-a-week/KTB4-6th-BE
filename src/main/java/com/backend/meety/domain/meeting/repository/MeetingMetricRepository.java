package com.backend.meety.domain.meeting.repository;

import com.backend.meety.domain.meeting.entity.MeetingMetric;
import java.math.BigDecimal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MeetingMetricRepository extends JpaRepository<MeetingMetric, Long> {

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
