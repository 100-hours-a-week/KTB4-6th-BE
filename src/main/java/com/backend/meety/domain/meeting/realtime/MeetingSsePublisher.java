package com.backend.meety.domain.meeting.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class MeetingSsePublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void broadcast(Long meetingId, String eventName, Object payload) {
        publish(new MeetingSseMessage(
                MeetingSseCommand.BROADCAST, meetingId, null, eventName, objectMapper.valueToTree(payload)));
    }

    public void completeParticipant(Long meetingId, Long userId) {
        publish(new MeetingSseMessage(MeetingSseCommand.COMPLETE_PARTICIPANT, meetingId, userId, null, null));
    }

    public void completeAll(Long meetingId) {
        publish(new MeetingSseMessage(MeetingSseCommand.COMPLETE_ALL, meetingId, null, null, null));
    }

    public void sendAndCompleteAll(Long meetingId, String eventName, Object payload) {
        publish(new MeetingSseMessage(
                MeetingSseCommand.SEND_AND_COMPLETE_ALL, meetingId, null, eventName, objectMapper.valueToTree(payload)));
    }

    private void publish(MeetingSseMessage message) {
        try {
            redisTemplate.convertAndSend(MeetingSseChannels.MEETING_SSE, objectMapper.writeValueAsString(message));
        } catch (RuntimeException e) {
            log.error("회의 SSE 메시지 발행에 실패했습니다. meetingId={}, command={}, eventName={}",
                    message.meetingId(), message.command(), message.eventName(), e);
        }
    }
}
