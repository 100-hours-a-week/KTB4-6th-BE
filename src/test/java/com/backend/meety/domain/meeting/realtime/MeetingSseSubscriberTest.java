package com.backend.meety.domain.meeting.realtime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import tools.jackson.databind.ObjectMapper;

class MeetingSseSubscriberTest {

    private final MeetingSseRegistry registry = mock(MeetingSseRegistry.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MeetingSseSubscriber subscriber = new MeetingSseSubscriber(registry, objectMapper);

    @Test
    @DisplayName("받은 명령에 따라 이 서버의 회의 SSE 연결로 전송하거나 연결을 닫는다")
    void dispatchesCommandsToLocalRegistry() {
        receive(new MeetingSseMessage(MeetingSseCommand.BROADCAST, 100L, null, "CHAT_FAILED",
                objectMapper.createObjectNode().put("messageId", 8801)));
        receive(new MeetingSseMessage(MeetingSseCommand.COMPLETE_PARTICIPANT, 100L, 10L, null, null));
        receive(new MeetingSseMessage(MeetingSseCommand.COMPLETE_ALL, 100L, null, null, null));
        receive(new MeetingSseMessage(MeetingSseCommand.SEND_AND_COMPLETE_ALL, 100L, null, "MEETING_DELETED",
                objectMapper.createObjectNode().put("meetingId", 100)));

        verify(registry).broadcast(100L, "CHAT_FAILED", objectMapper.createObjectNode().put("messageId", 8801));
        verify(registry).complete(100L, 10L);
        verify(registry).completeAll(100L);
        verify(registry).sendAndCompleteAll(100L, "MEETING_DELETED", objectMapper.createObjectNode().put("meetingId", 100));
    }

    @Test
    @DisplayName("읽을 수 없는 메시지는 무시하고 예외를 던지지 않는다")
    void ignoresMalformedMessage() {
        assertThatCode(() -> subscriber.onMessage(message("not-json"), null)).doesNotThrowAnyException();

        verify(registry, never()).broadcast(anyLong(), anyString(), any());
    }

    private void receive(MeetingSseMessage message) {
        subscriber.onMessage(message(objectMapper.writeValueAsString(message)), null);
    }

    private DefaultMessage message(String body) {
        return new DefaultMessage("meeting-sse".getBytes(StandardCharsets.UTF_8), body.getBytes(StandardCharsets.UTF_8));
    }
}
