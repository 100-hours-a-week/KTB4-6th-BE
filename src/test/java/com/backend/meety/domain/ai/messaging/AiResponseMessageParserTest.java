package com.backend.meety.domain.ai.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.meety.domain.ai.entity.AiRequestType;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class AiResponseMessageParserTest {

    private final AiResponseMessageParser parser = new AiResponseMessageParser(new ObjectMapper());

    @Test
    @DisplayName("성공 응답을 파싱한다")
    void parsesSucceeded() {
        AiResponseMessage message = parser.parse(bytes("""
                {"schemaVersion":1,"aiRequestId":101,"processingToken":"token-1","requestType":"SUMMARY",
                 "eventType":"SUCCEEDED","result":{"summary":"요약"}}
                """)).orElseThrow();

        assertThat(message.aiRequestId()).isEqualTo(101L);
        assertThat(message.processingToken()).isEqualTo("token-1");
        assertThat(message.requestType()).isEqualTo(AiRequestType.SUMMARY);
        assertThat(message.eventType()).isEqualTo(AiResponseEventType.SUCCEEDED);
        assertThat(message.result().get("summary").asString()).isEqualTo("요약");
    }

    @Test
    @DisplayName("실패 응답을 파싱한다")
    void parsesFailed() {
        AiResponseMessage message = parser.parse(bytes("""
                {"schemaVersion":1,"aiRequestId":101,"processingToken":"token-1","requestType":"METRIC",
                 "eventType":"FAILED","error":{"code":"MODEL_TIMEOUT"}}
                """)).orElseThrow();

        assertThat(message.eventType()).isEqualTo(AiResponseEventType.FAILED);
        assertThat(message.error()).isEqualTo(new AiResponseError("MODEL_TIMEOUT"));
    }

    @Test
    @DisplayName("모르는 필드는 무시한다")
    void ignoresUnknownFields() {
        assertThat(parser.parse(bytes("""
                {"schemaVersion":1,"aiRequestId":101,"processingToken":"token-1","requestType":"CHAT",
                 "eventType":"SUCCEEDED","result":{},"elapsedMs":1200}
                """))).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-json",
            "[]",
            "null",
            """
            {"aiRequestId":101,"processingToken":"t","requestType":"SUMMARY","eventType":"SUCCEEDED","result":{}}
            """,
            """
            {"schemaVersion":2,"aiRequestId":101,"processingToken":"t","requestType":"SUMMARY","eventType":"SUCCEEDED","result":{}}
            """,
            """
            {"schemaVersion":1,"processingToken":"t","requestType":"SUMMARY","eventType":"SUCCEEDED","result":{}}
            """,
            """
            {"schemaVersion":1,"aiRequestId":101,"processingToken":" ","requestType":"SUMMARY","eventType":"SUCCEEDED","result":{}}
            """,
            """
            {"schemaVersion":1,"aiRequestId":101,"processingToken":"t","eventType":"SUCCEEDED","result":{}}
            """,
            """
            {"schemaVersion":1,"aiRequestId":101,"processingToken":"t","requestType":"UNKNOWN","eventType":"SUCCEEDED","result":{}}
            """,
            """
            {"schemaVersion":1,"aiRequestId":101,"processingToken":"t","requestType":"GLOBAL_CHAT","eventType":"SUCCEEDED","result":{}}
            """,
            """
            {"schemaVersion":1,"aiRequestId":101,"processingToken":"t","requestType":"SUMMARY","result":{}}
            """,
            """
            {"schemaVersion":1,"aiRequestId":101,"processingToken":"t","requestType":"SUMMARY","eventType":"STARTED"}
            """,
            """
            {"schemaVersion":1,"aiRequestId":101,"processingToken":"t","requestType":"SUMMARY","eventType":"SUCCEEDED"}
            """,
            """
            {"schemaVersion":1,"aiRequestId":101,"processingToken":"t","requestType":"SUMMARY","eventType":"SUCCEEDED","result":null}
            """,
            """
            {"schemaVersion":1,"aiRequestId":101,"processingToken":"t","requestType":"SUMMARY","eventType":"FAILED"}
            """,
            """
            {"schemaVersion":1,"aiRequestId":101,"processingToken":"t","requestType":"SUMMARY","eventType":"FAILED","error":{"message":"x"}}
            """
    })
    @DisplayName("형식이 잘못된 응답은 파싱 결과가 없다")
    void rejectsInvalidMessage(String body) {
        assertThat(parser.parse(bytes(body))).isEmpty();
    }

    private byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }
}
