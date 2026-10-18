package com.backend.meety.domain.meeting.realtime;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;

import java.nio.charset.StandardCharsets;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 도메인 payload 검증용 동기 loopback. 실제 Redis/비동기 실행 경계는 IntegrationTest에서 검증한다.
 */
public final class MeetingSseLoopback {

    private static final String CHANNEL = "meeting-sse";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private MeetingSseLoopback() {
    }

    public static MeetingSsePublisher publisher(MeetingSseRegistry registry) {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        doAnswer(call -> {
            call.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        MeetingSseDispatcher dispatcher = new MeetingSseDispatcher(
                executor, new MeetingSseQueueProperties(100, 1000), registry, meters);
        MeetingSseSubscriber subscriber = new MeetingSseSubscriber(dispatcher, OBJECT_MAPPER, meters);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.convertAndSend(eq(CHANNEL), anyString())).thenAnswer(call -> {
            byte[] body = call.<String>getArgument(1).getBytes(StandardCharsets.UTF_8);
            subscriber.onMessage(new DefaultMessage(CHANNEL.getBytes(StandardCharsets.UTF_8), body), null);
            return 1L;
        });
        return new MeetingSsePublisher(redisTemplate, OBJECT_MAPPER);
    }

    public static JsonNode json(Object payload) {
        return OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsString(payload));
    }
}
