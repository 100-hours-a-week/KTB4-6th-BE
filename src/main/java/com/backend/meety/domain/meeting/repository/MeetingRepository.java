package com.backend.meety.domain.meeting.repository;

import com.backend.meety.domain.meeting.entity.Meeting;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MeetingRepository extends JpaRepository<Meeting, Long> {

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
}
