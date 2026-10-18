package com.backend.meety.domain.recording.owner;

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
public class RecordingCommandSubscriber implements MessageListener {

    private final RecordingCommandHandler commandHandler;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            commandHandler.handle(objectMapper.readValue(
                    new String(message.getBody(), StandardCharsets.UTF_8), RecordingCommandMessage.class));
        } catch (RuntimeException e) {
            log.error("녹음 제어 명령 처리에 실패했습니다.", e);
        }
    }
}
