package com.backend.meety.domain.meeting.entity;

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
@Table(name = "meeting_participants")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MeetingParticipant extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meeting_id", nullable = false)
    private Meeting meeting;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_member_id", nullable = false)
    private TeamMember teamMember;

    @Enumerated(EnumType.STRING)
    @Column(name = "participation_status", nullable = false, length = 30)
    private ParticipationStatus participationStatus = ParticipationStatus.JOINED;

    public static MeetingParticipant create(Meeting meeting, TeamMember teamMember) {
        MeetingParticipant meetingParticipant = new MeetingParticipant();
        meetingParticipant.meeting = meeting;
        meetingParticipant.teamMember = teamMember;
        meetingParticipant.participationStatus = ParticipationStatus.JOINED;
        meetingParticipant.restoreDeleted();
        return meetingParticipant;
    }

    public void rejoin() {
        this.participationStatus = ParticipationStatus.JOINED;
        restoreDeleted();
    }

    public void leave(LocalDateTime leftAt) {
        this.participationStatus = ParticipationStatus.LEFT;
        markDeleted(leftAt);
    }

    public boolean isJoined() {
        return participationStatus == ParticipationStatus.JOINED && getDeletedAt() == null;
    }

    public boolean isLeft() {
        return participationStatus == ParticipationStatus.LEFT;
    }

    public boolean isDisconnected() {
        return participationStatus == ParticipationStatus.DISCONNECTED;
    }
}
