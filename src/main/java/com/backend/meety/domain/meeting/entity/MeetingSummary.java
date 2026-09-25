package com.backend.meety.domain.meeting.entity;

import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "meeting_summaries",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_meeting_summaries_meeting_id_version",
                columnNames = {"meeting_id", "version"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MeetingSummary extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ai_request_id", nullable = false, unique = true)
    private AiRequest aiRequest;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meeting_id", nullable = false)
    private Meeting meeting;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "version", nullable = false)
    private Long version = 1L;

    private MeetingSummary(AiRequest aiRequest, Team team, Meeting meeting, Long version) {
        this.aiRequest = aiRequest;
        this.team = team;
        this.meeting = meeting;
        this.version = version;
    }

    public static MeetingSummary createPending(AiRequest aiRequest, Team team, Meeting meeting, Long version) {
        return new MeetingSummary(aiRequest, team, meeting, version);
    }

    public void complete(String content) {
        this.content = content;
    }
}
