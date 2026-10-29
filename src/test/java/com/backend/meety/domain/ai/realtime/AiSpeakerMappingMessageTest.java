package com.backend.meety.domain.ai.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AiSpeakerMappingMessageTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesNullAndMappedSpeakerSnapshot() throws Exception {
        var root = mapper.readTree("""
                {"type":"speaker.mapping.updated","meetingId":42,"recordingSessionId":88,
                 "payload":{"mappings":[{"speakerId":0,"teamMemberId":10},{"speakerId":1,"teamMemberId":null}]}}
                """);

        AiSpeakerMappingMessage message = AiSpeakerMappingMessage.from(root);

        assertThat(message.recordingSessionId()).isEqualTo(88L);
        assertThat(message.mappings()).containsExactly(
                new AiSpeakerMappingMessage.Mapping(0L, 10L),
                new AiSpeakerMappingMessage.Mapping(1L, null));
    }

    @Test
    void rejectsDuplicateSpeakerOrMemberAssignment() throws Exception {
        assertThatThrownBy(() -> AiSpeakerMappingMessage.from(json("""
                [{"speakerId":0,"teamMemberId":10},{"speakerId":0,"teamMemberId":11}]
                """))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiSpeakerMappingMessage.from(json("""
                [{"speakerId":0,"teamMemberId":10},{"speakerId":1,"teamMemberId":10}]
                """))).isInstanceOf(IllegalArgumentException.class);
    }

    private tools.jackson.databind.JsonNode json(String mappings) throws Exception {
        return mapper.readTree("{\"meetingId\":42,\"recordingSessionId\":88,\"payload\":{\"mappings\":"
                + mappings + "}}");
    }
}
