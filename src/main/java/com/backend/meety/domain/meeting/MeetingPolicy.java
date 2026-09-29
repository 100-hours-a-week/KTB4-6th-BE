package com.backend.meety.domain.meeting;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "meeting")
public record MeetingPolicy(
        long dailyCreateLimit
) {
}
