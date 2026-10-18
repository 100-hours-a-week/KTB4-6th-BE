package com.backend.meety.domain.meeting.realtime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import tools.jackson.databind.ObjectMapper;

class MeetingSseSubscriberTest {

    private final MeetingSseDispatcher dispatcher = mock(MeetingSseDispatcher.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MeetingSseSubscriber subscriber = new MeetingSseSubscriber(
            dispatcher, objectMapper, new SimpleMeterRegistry());

    @Test
    @DisplayName("전송과 연결 종료를 포함한 모든 명령을 같은 Dispatcher에 제출한다")
    void dispatchesAllCommandsToOrderedDispatcher() {
        // 테스트 목적:
        // BROADCAST뿐 아니라 연결 종료 명령도 같은 순서 보장 경계를 거쳐
        // 앞선 전송을 추월하지 않도록 모두 Dispatcher에 제출하는지 검증한다.

        // given

        // when
        receive(new MeetingSseMessage(MeetingSseCommand.BROADCAST, 100L, null, "CHAT_FAILED",
                objectMapper.createObjectNode().put("messageId", 8801)));
        receive(new MeetingSseMessage(MeetingSseCommand.COMPLETE_PARTICIPANT, 100L, 10L, null, null));
        receive(new MeetingSseMessage(MeetingSseCommand.COMPLETE_ALL, 100L, null, null, null));
        receive(new MeetingSseMessage(MeetingSseCommand.SEND_AND_COMPLETE_ALL, 100L, null, "MEETING_DELETED",
                objectMapper.createObjectNode().put("meetingId", 100)));

        // then
        verify(dispatcher).dispatch(new MeetingSseMessage(MeetingSseCommand.BROADCAST, 100L, null, "CHAT_FAILED",
                objectMapper.createObjectNode().put("messageId", 8801)));
        verify(dispatcher).dispatch(new MeetingSseMessage(MeetingSseCommand.COMPLETE_PARTICIPANT, 100L, 10L,
                null, objectMapper.readTree("null")));
        verify(dispatcher).dispatch(new MeetingSseMessage(MeetingSseCommand.COMPLETE_ALL, 100L, null,
                null, objectMapper.readTree("null")));
        verify(dispatcher).dispatch(new MeetingSseMessage(MeetingSseCommand.SEND_AND_COMPLETE_ALL, 100L, null, "MEETING_DELETED",
                objectMapper.createObjectNode().put("meetingId", 100)));
    }

    @Test
    @DisplayName("읽을 수 없는 메시지는 무시하고 예외를 던지지 않는다")
    void ignoresMalformedMessage() {
        // 테스트 목적:
        // 역직렬화할 수 없는 Redis 메시지는 비동기 큐에 넣지 않고
        // 리스너 밖으로 예외를 전파하지 않는지 검증한다.

        // given

        // when, then
        assertThatCode(() -> subscriber.onMessage(message("not-json"), null)).doesNotThrowAnyException();

        verify(dispatcher, never()).dispatch(any());
    }

    private void receive(MeetingSseMessage message) {
        subscriber.onMessage(message(objectMapper.writeValueAsString(message)), null);
    }

    private DefaultMessage message(String body) {
        return new DefaultMessage("meeting-sse".getBytes(StandardCharsets.UTF_8), body.getBytes(StandardCharsets.UTF_8));
    }
}
