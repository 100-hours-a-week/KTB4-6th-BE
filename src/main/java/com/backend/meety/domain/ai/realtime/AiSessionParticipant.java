package com.backend.meety.domain.ai.realtime;

import com.backend.meety.domain.meeting.entity.MeetingParticipant;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.user.entity.User;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public record AiSessionParticipant(
        Long teamMemberId,
        String name,
        @JsonInclude(JsonInclude.Include.ALWAYS) SpeakerEmbedding speakerEmbedding
) {

    public record SpeakerEmbedding(String modelVersion, List<Double> embedding) { }

    public static AiSessionParticipant from(MeetingParticipant attendee) {
        TeamMember teamMember = attendee.getTeamMember();
        if (teamMember == null) {
            return new AiSessionParticipant(null, attendee.getDisplayName(), null);
        }
        User user = teamMember.getUser();
        SpeakerEmbedding embedding = user != null && user.hasVoiceProfile()
                ? new SpeakerEmbedding(user.getEmbeddingVersion(), user.getVoiceEmbedding()) : null;
        return new AiSessionParticipant(teamMember.getId(), teamMember.getDisplayName(), embedding);
    }
}
