package com.backend.meety.domain.meeting.entity;

import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "meetings")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Meeting extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_team_member_id", nullable = false)
    private TeamMember createdByTeamMember;

    @Column(name = "title", nullable = false, length = 20)
    private String title;

    @Column(name = "purpose", nullable = false, length = 100)
    private String purpose;

    @Column(name = "note", length = 200)
    private String note;

    @Column(name = "scheduled_at", nullable = false)
    private LocalDateTime scheduledAt;

    @Column(name = "target_duration_minutes", nullable = false, columnDefinition = "SMALLINT")
    private Integer targetDurationMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private MeetingStatus status = MeetingStatus.WAITING;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    public static Meeting create(
            Team team,
            TeamMember createdByTeamMember,
            String title,
            String purpose,
            String note,
            LocalDateTime scheduledAt,
            Integer targetDurationMinutes
    ) {
        Meeting meeting = new Meeting();
        meeting.team = team;
        meeting.createdByTeamMember = createdByTeamMember;
        meeting.title = title;
        meeting.purpose = purpose;
        meeting.note = note;
        meeting.scheduledAt = scheduledAt;
        meeting.targetDurationMinutes = targetDurationMinutes;
        meeting.status = MeetingStatus.WAITING;
        return meeting;
    }

    public void updateWaitingInfo(
            String title,
            String purpose,
            String note,
            LocalDateTime scheduledAt,
            Integer targetDurationMinutes
    ) {
        if (title != null) {
            this.title = title;
        }
        if (purpose != null) {
            this.purpose = purpose;
        }
        if (note != null) {
            this.note = note.isEmpty() ? null : note;
        }
        if (scheduledAt != null) {
            this.scheduledAt = scheduledAt;
        }
        if (targetDurationMinutes != null) {
            this.targetDurationMinutes = targetDurationMinutes;
        }
    }

    public void rename(String title) {
        if (title != null) {
            this.title = title;
        }
    }

    public void start(LocalDateTime now) {
        status = MeetingStatus.IN_PROGRESS;
        startedAt = now;
    }

    public void complete(LocalDateTime now) {
        status = MeetingStatus.COMPLETED;
        endedAt = now;
    }

    public void softDelete(LocalDateTime deletedAt) {
        markDeleted(deletedAt);
    }
}
