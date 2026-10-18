package com.backend.meety.domain.meeting.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

class MeetingSsePublisherTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MeetingSsePublisher publisher =
            new MeetingSsePublisher(redisTemplate, objectMapper);

    @Test
    @DisplayName("이벤트를 meeting-sse 채널에 회의 ID·이벤트 이름·payload JSON으로 발행한다")
    void publishesBroadcastMessage() {
        publisher.broadcast(100L, "CHAT_FAILED", Map.of("messageId", 8801));

        MeetingSseMessage message = publishedMessage();
        assertThat(message.command()).isEqualTo(MeetingSseCommand.BROADCAST);
        assertThat(message.meetingId()).isEqualTo(100L);
        assertThat(message.eventName()).isEqualTo("CHAT_FAILED");
        assertThat(message.payload().get("messageId").asLong()).isEqualTo(8801L);
    }

    @Test
    @DisplayName("연결 종료 명령은 대상 회의와 사용자만 담아 발행한다")
    void publishesCloseCommands() {
        publisher.completeParticipant(100L, 10L);
        MeetingSseMessage participant = publishedMessage();
        assertThat(participant.command()).isEqualTo(MeetingSseCommand.COMPLETE_PARTICIPANT);
        assertThat(participant.userId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("Redis 발행에 실패해도 예외를 밖으로 던지지 않는다")
    void doesNotThrowWhenRedisFails() {
        when(redisTemplate.convertAndSend(eq("meeting-sse"), anyString()))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> publisher.completeAll(100L)).doesNotThrowAnyException();
    }

    private MeetingSseMessage publishedMessage() {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(eq("meeting-sse"), body.capture());
        return objectMapper.readValue(body.getValue(), MeetingSseMessage.class);
    }
}
