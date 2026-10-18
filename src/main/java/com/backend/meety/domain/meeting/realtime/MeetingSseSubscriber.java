package com.backend.meety.domain.meeting.realtime;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
public class MeetingSseSubscriber implements MessageListener {

    private final MeetingSseDispatcher dispatcher;
    private final ObjectMapper objectMapper;
    private final Timer receiveDuration;

    public MeetingSseSubscriber(MeetingSseDispatcher dispatcher, ObjectMapper objectMapper, MeterRegistry meters) {
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
        receiveDuration = meters.timer("meeting.sse.subscriber");
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        long started = System.nanoTime();
        try {
            dispatcher.dispatch(objectMapper.readValue(
                    new String(message.getBody(), StandardCharsets.UTF_8), MeetingSseMessage.class));
        } catch (RuntimeException e) {
            log.error("회의 SSE 메시지 처리에 실패했습니다.", e);
        } finally {
            receiveDuration.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }
}
