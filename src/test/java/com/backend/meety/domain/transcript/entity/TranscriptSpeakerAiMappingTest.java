package com.backend.meety.domain.transcript.entity;

import static com.backend.meety.domain.recording.RecordingFixtures.member;
import static com.backend.meety.domain.recording.RecordingFixtures.team;
import static org.assertj.core.api.Assertions.assertThat;

import com.backend.meety.domain.meeting.entity.Meeting;
import org.junit.jupiter.api.Test;

class TranscriptSpeakerAiMappingTest {
    @Test
    void appliesMappingChangesAndPreservesManualAlias() {
        var team = team();
        var member = member(team);
        var meeting = Meeting.create(team, member, "회의", "목적", null,
                java.time.LocalDateTime.now(), 30);
        TranscriptSpeaker speaker = TranscriptSpeaker.create(meeting, "화자 1");

        speaker.applyAiMapping(member);
        assertThat(speaker.getMappedTeamMember()).isSameAs(member);
        assertThat(speaker.displayName()).isEqualTo("시작자");
        speaker.applyAiMapping(null);
        assertThat(speaker.getMappedTeamMember()).isNull();
        speaker.mapToCustomAlias("직접 지정");
        speaker.applyAiMapping(member);
        assertThat(speaker.getCustomAlias()).isEqualTo("직접 지정");
        assertThat(speaker.getMappedTeamMember()).isNull();
    }
}
