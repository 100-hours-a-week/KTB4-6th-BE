package com.backend.meety.domain.transcript.dto;

import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.transcript.entity.TranscriptSpeaker;

public record TranscriptSpeakerResponse(
        Long transcriptSpeakerId,
        String speakerLabel,
        Long mappedTeamMemberId,
        String customAlias
) {

    public static TranscriptSpeakerResponse from(TranscriptSpeaker speaker) {
        TeamMember mappedTeamMember = speaker.getMappedTeamMember();
        return new TranscriptSpeakerResponse(
                speaker.getId(),
                speaker.getSpeakerLabel(),
                mappedTeamMember == null ? null : mappedTeamMember.getId(),
                speaker.getCustomAlias()
        );
    }
}
