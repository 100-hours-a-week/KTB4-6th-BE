package com.backend.meety.domain.transcript.entity;

import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "transcript_speakers")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TranscriptSpeaker extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meeting_id", nullable = false)
    private Meeting meeting;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mapped_team_member_id")
    private TeamMember mappedTeamMember;

    @Column(name = "speaker_label", nullable = false, length = 10)
    private String speakerLabel;

    @Column(name = "custom_alias", length = 10)
    private String customAlias;

    public void mapToTeamMember(TeamMember teamMember) {
        this.mappedTeamMember = teamMember;
        this.customAlias = null;
    }

    public void mapToCustomAlias(String customAlias) {
        this.mappedTeamMember = null;
        this.customAlias = customAlias;
    }

    public void clearMapping() {
        this.mappedTeamMember = null;
        this.customAlias = null;
    }
}
