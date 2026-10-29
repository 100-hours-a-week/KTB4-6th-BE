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
import jakarta.persistence.UniqueConstraint;
import com.backend.meety.domain.recording.entity.RecordingSession;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "transcript_speakers", uniqueConstraints = @UniqueConstraint(
        name = "uk_transcript_speakers_recording_ai_speaker",
        columnNames = {"recording_session_id", "ai_speaker_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TranscriptSpeaker extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meeting_id", nullable = false)
    private Meeting meeting;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recording_session_id")
    private RecordingSession recordingSession;

    @Column(name = "ai_speaker_id")
    private Long aiSpeakerId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mapped_team_member_id")
    private TeamMember mappedTeamMember;

    @Column(name = "speaker_label", nullable = false, length = 10)
    private String speakerLabel;

    @Column(name = "custom_alias", length = 10)
    private String customAlias;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "mapping_source", length = 20)
    private TranscriptSpeakerMappingSource mappingSource;

    public static TranscriptSpeaker create(Meeting meeting, String speakerLabel) {
        TranscriptSpeaker speaker = new TranscriptSpeaker();
        speaker.meeting = meeting;
        speaker.speakerLabel = speakerLabel;
        return speaker;
    }

    public static TranscriptSpeaker createAiSpeaker(
            Meeting meeting, RecordingSession recordingSession, Long aiSpeakerId, String speakerLabel) {
        TranscriptSpeaker speaker = create(meeting, speakerLabel);
        speaker.recordingSession = recordingSession;
        speaker.aiSpeakerId = aiSpeakerId;
        return speaker;
    }

    public void applyAiMapping(TeamMember teamMember) {
        if (isManualMapping()) {
            return;
        }
        this.mappedTeamMember = teamMember;
        this.customAlias = null;
        this.mappingSource = TranscriptSpeakerMappingSource.AI;
    }

    public boolean isAiMapped() {
        return mappingSource == TranscriptSpeakerMappingSource.AI;
    }

    public void mapToTeamMember(TeamMember teamMember) {
        this.mappedTeamMember = teamMember;
        this.customAlias = null;
        this.mappingSource = TranscriptSpeakerMappingSource.MANUAL;
    }

    public void mapToCustomAlias(String customAlias) {
        this.mappedTeamMember = null;
        this.customAlias = customAlias;
        this.mappingSource = TranscriptSpeakerMappingSource.MANUAL;
    }

    public void clearMapping() {
        this.mappedTeamMember = null;
        this.customAlias = null;
        this.mappingSource = TranscriptSpeakerMappingSource.MANUAL;
    }

    private boolean isManualMapping() {
        return mappingSource == TranscriptSpeakerMappingSource.MANUAL
                || (mappingSource == null && (mappedTeamMember != null || customAlias != null));
    }

    public String displayName() {
        if (customAlias != null) { return customAlias; }
        if (mappedTeamMember != null) { return mappedTeamMember.getDisplayName(); }
        return speakerLabel;
    }
}
