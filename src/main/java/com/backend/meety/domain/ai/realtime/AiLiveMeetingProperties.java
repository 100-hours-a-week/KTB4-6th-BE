package com.backend.meety.domain.ai.realtime;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai")
public record AiLiveMeetingProperties(
        URI websocketUrl
) {
}
