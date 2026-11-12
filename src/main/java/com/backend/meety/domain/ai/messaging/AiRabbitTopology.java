package com.backend.meety.domain.ai.messaging;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AiRabbitTopology {

    public static final String REQUEST_EXCHANGE = "meety.ai.request";
    public static final String RESPONSE_EXCHANGE = "meety.ai.response";
    public static final String RESPONSE_DLX = "meety.ai.response.dlx";

    public static final String SUMMARY_ROUTING_KEY = "summary";
    public static final String DIARIZATION_ROUTING_KEY = "diarization";
    public static final String METRIC_ROUTING_KEY = "metric";
    public static final String CHAT_ROUTING_KEY = "chat";
    public static final String SPEAKER_EMBEDDING_ROUTING_KEY = "speaker-embedding";
    public static final String RESPONSE_ROUTING_KEY = "event";
    public static final String DEAD_LETTER_ROUTING_KEY = "invalid";

    public static final String SUMMARY_REQUEST_QUEUE = "meety.ai.summary.request";
    public static final String DIARIZATION_REQUEST_QUEUE = "meety.ai.diarization.request";
    public static final String METRIC_REQUEST_QUEUE = "meety.ai.metric.request";
    public static final String CHAT_REQUEST_QUEUE = "meety.ai.chat.request";
    public static final String SPEAKER_EMBEDDING_REQUEST_QUEUE = "meety.ai.speaker-embedding.request";
    public static final String RESPONSE_QUEUE = "meety.ai.response.events";
    public static final String RESPONSE_DLQ = "meety.ai.response.dlq";
}
