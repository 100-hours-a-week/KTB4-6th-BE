package com.backend.meety.domain.transcript.dto;

import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.transcript.entity.TranscriptSpeaker;

public record TranscriptSpeakerMappingStateResponse(
        Long transcriptSpeakerId,
        String speakerLabel,
        String displayName,
        TranscriptSpeakerMappingType mappingType,
        Long mappedTeamMemberId,
        String customAlias
) {

    public static TranscriptSpeakerMappingStateResponse from(TranscriptSpeaker speaker) {
        TeamMember mappedTeamMember = speaker.getMappedTeamMember();
        TranscriptSpeakerMappingType mappingType = resolveMappingType(speaker);
        return new TranscriptSpeakerMappingStateResponse(
                speaker.getId(),
                speaker.getSpeakerLabel(),
                resolveDisplayName(speaker, mappedTeamMember),
                mappingType,
                mappedTeamMember == null ? null : mappedTeamMember.getId(),
                speaker.getCustomAlias()
        );
    }

    private static TranscriptSpeakerMappingType resolveMappingType(TranscriptSpeaker speaker) {
        if (speaker.getMappedTeamMember() != null) {
            return TranscriptSpeakerMappingType.TEAM_MEMBER;
        }
        if (speaker.getCustomAlias() != null) {
            return TranscriptSpeakerMappingType.CUSTOM_ALIAS;
        }
        return TranscriptSpeakerMappingType.NONE;
    }

    private static String resolveDisplayName(TranscriptSpeaker speaker, TeamMember mappedTeamMember) {
        if (speaker.getCustomAlias() != null) {
            return speaker.getCustomAlias();
        }
        if (mappedTeamMember != null) {
            return mappedTeamMember.getDisplayName();
        }
        return speaker.getSpeakerLabel();
    }
}
