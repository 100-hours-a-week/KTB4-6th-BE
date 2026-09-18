package com.backend.meety.domain.recording.entity;

import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.recording.exception.RecordingErrorCode;
import com.backend.meety.domain.recording.exception.RecordingException;
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
@Table(name = "recording_sessions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecordingSession extends BaseEntity {

    private static final int MAX_RECORDING_MINUTES = 90;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meeting_id", nullable = false)
    private Meeting meeting;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "started_by_team_member_id", nullable = false)
    private TeamMember startedByTeamMember;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private RecordingSessionStatus status = RecordingSessionStatus.PREPARING;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "paused_at")
    private LocalDateTime pausedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "auto_end_at")
    private LocalDateTime autoEndAt;

    public static RecordingSession start(Meeting meeting, TeamMember startedBy, LocalDateTime now) {
        RecordingSession session = new RecordingSession();
        session.meeting = meeting;
        session.startedByTeamMember = startedBy;
        session.status = RecordingSessionStatus.RECORDING;
        session.startedAt = now;
        session.autoEndAt = now.plusMinutes(MAX_RECORDING_MINUTES);
        return session;
    }

    public void pause(LocalDateTime now) {
        requireStatus(RecordingSessionStatus.RECORDING);
        status = RecordingSessionStatus.PAUSED;
        pausedAt = now;
    }

    public void resume(LocalDateTime now) {
        requireStatus(RecordingSessionStatus.PAUSED);
        if (!now.isBefore(autoEndAt)) {
            throw new RecordingException(RecordingErrorCode.RECORDING_AUTO_END_REACHED);
        }
        status = RecordingSessionStatus.RECORDING;
        pausedAt = null;
    }

    public void complete(LocalDateTime now) {
        if (status != RecordingSessionStatus.RECORDING && status != RecordingSessionStatus.PAUSED) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_STATUS_TRANSITION);
        }
        status = RecordingSessionStatus.COMPLETED;
        pausedAt = null;
        endedAt = now;
    }

    private void requireStatus(RecordingSessionStatus expected) {
        if (status != expected) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_STATUS_TRANSITION);
        }
    }
}
