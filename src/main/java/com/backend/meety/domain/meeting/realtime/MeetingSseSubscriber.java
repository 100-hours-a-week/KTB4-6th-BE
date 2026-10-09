package com.backend.meety.domain.meeting.realtime;

import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class MeetingSseSubscriber implements MessageListener {

    private final MeetingSseRegistry registry;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            handle(objectMapper.readValue(new String(message.getBody(), StandardCharsets.UTF_8), MeetingSseMessage.class));
        } catch (RuntimeException e) {
            log.error("회의 SSE 메시지 처리에 실패했습니다.", e);
        }
    }

    void handle(MeetingSseMessage message) {
        switch (message.command()) {
            case BROADCAST -> {
                int emitterCount = registry.broadcast(message.meetingId(), message.eventName(), message.payload());
                log.debug("회의 SSE 전송 완료. meetingId={}, event={}, emitterCount={}",
                        message.meetingId(), message.eventName(), emitterCount);
            }
            case COMPLETE_PARTICIPANT -> registry.complete(message.meetingId(), message.userId());
            case COMPLETE_ALL -> registry.completeAll(message.meetingId());
            case SEND_AND_COMPLETE_ALL ->
                    registry.sendAndCompleteAll(message.meetingId(), message.eventName(), message.payload());
        }
    }
}
