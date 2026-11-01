package com.backend.meety.domain.ai.assistant;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai.global-chat")
public record AssistantChatProperties(
        Duration executionTimeout,
        Duration streamTimeout,
        Duration readTimeout,
        int historyMessageLimit,
        int pageSize,
        int maxPageSize
) {
    public AssistantChatProperties {
        executionTimeout = executionTimeout == null ? Duration.ofSeconds(180) : executionTimeout;
        streamTimeout = streamTimeout == null ? Duration.ofSeconds(200) : streamTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(30) : readTimeout;
        historyMessageLimit = historyMessageLimit <= 0 ? 20 : Math.min(historyMessageLimit, 20);
        pageSize = pageSize <= 0 ? 20 : pageSize;
        maxPageSize = maxPageSize <= 0 ? 50 : maxPageSize;
    }
}
