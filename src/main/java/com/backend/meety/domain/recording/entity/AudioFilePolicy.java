package com.backend.meety.domain.recording.entity;

import java.time.Duration;

public final class AudioFilePolicy {

    public static final String CONTENT_TYPE = "audio/mp4";
    public static final String EXTENSION = "mp4";
    public static final Duration UPLOAD_URL_VALIDITY = Duration.ofMinutes(10);
    public static final Duration DOWNLOAD_URL_VALIDITY = Duration.ofMinutes(10);
    public static final Duration RETENTION = Duration.ofDays(100);

    private AudioFilePolicy() {
    }

    public static boolean isAllowedContentType(String contentType) {
        return CONTENT_TYPE.equals(contentType);
    }
}
