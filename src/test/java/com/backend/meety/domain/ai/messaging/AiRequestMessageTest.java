package com.backend.meety.domain.ai.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.meety.domain.ai.client.ChatAiHistoryMessage;
import com.backend.meety.domain.ai.client.ChatAiRequest;
import com.backend.meety.domain.ai.client.ChatAiTranscriptSegment;
import com.backend.meety.domain.ai.client.DiarizationAiRequest;
import com.backend.meety.domain.ai.client.DiarizationAiSegment;
import com.backend.meety.domain.ai.client.MetricAiRequest;
import com.backend.meety.domain.ai.client.MetricAiSegment;
import com.backend.meety.domain.ai.client.SummaryAiRequest;
import com.backend.meety.domain.ai.client.SummaryAiSegment;
import com.backend.meety.domain.ai.client.SummaryAiSpeaker;
import com.backend.meety.domain.ai.entity.AiRequestType;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AiRequestMessageTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @ParameterizedTest(name = "{0}")
    @MethodSource("aiInputs")
    @DisplayName("공통 envelope에 기존 HTTP 요청 본문을 input으로 그대로 담는다")
    void wrapsHttpRequestBodyAsInput(AiRequestType requestType, Object input) {
        AiRequestMessage message = AiRequestMessage.create(101L, "token-1", requestType, input);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(message));

        assertThat(json.propertyNames()).containsExactlyInAnyOrder(
                "schemaVersion", "aiRequestId", "processingToken", "requestType", "input");
        assertThat(json.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(json.get("aiRequestId").asLong()).isEqualTo(101L);
        assertThat(json.get("processingToken").asString()).isEqualTo("token-1");
        assertThat(json.get("requestType").asString()).isEqualTo(requestType.name());
        assertThat(json.get("input")).isEqualTo(objectMapper.readTree(objectMapper.writeValueAsString(input)));
    }

    @Test
    @DisplayName("MQ 대상 작업 유형은 토폴로지의 Routing Key로 매핑한다")
    void mapsRequestTypeToRoutingKey() {
        assertThat(AiRabbitTopology.requestRoutingKey(AiRequestType.SUMMARY)).hasValue("summary");
        assertThat(AiRabbitTopology.requestRoutingKey(AiRequestType.DIARIZATION)).hasValue("diarization");
        assertThat(AiRabbitTopology.requestRoutingKey(AiRequestType.METRIC)).hasValue("metric");
        assertThat(AiRabbitTopology.requestRoutingKey(AiRequestType.CHAT)).hasValue("chat");
    }

    @Test
    @DisplayName("MQ 대상이 아닌 작업 유형은 Routing Key가 없다")
    void excludesNonMqRequestTypes() {
        assertThat(AiRabbitTopology.requestRoutingKey(AiRequestType.GLOBAL_CHAT)).isEmpty();
        assertThat(AiRabbitTopology.requestRoutingKey(AiRequestType.REPORT)).isEmpty();
    }

    static Stream<Arguments> aiInputs() {
        return Stream.of(
                Arguments.of(AiRequestType.SUMMARY, new SummaryAiRequest(
                        "101", 7L, "주간 회의", "진행 점검", null, null, "2026-11-14T10:00:00",
                        List.of(new SummaryAiSpeaker(1L, 11L, "민수")),
                        List.of(new SummaryAiSegment(31L, 1L, "민수", 1L, "안건을 시작합니다", 0L, 1_500L)))),
                Arguments.of(AiRequestType.METRIC, new MetricAiRequest(
                        "102", 7L, "요약 내용",
                        List.of(new MetricAiSegment(31L, 1L, 1L, "안건을 시작합니다", 0L, 1_500L)))),
                Arguments.of(AiRequestType.DIARIZATION, new DiarizationAiRequest(
                        "103", 7L, "https://bucket.s3/audio?signature=abc",
                        List.of(new DiarizationAiSegment(31L, "안건을 시작합니다", 0L, 1_500L)))),
                Arguments.of(AiRequestType.CHAT, new ChatAiRequest(
                        104L, 7L, 3L, "결정 사항이 뭐였지?",
                        List.of(ChatAiHistoryMessage.user("요약해줘"), ChatAiHistoryMessage.assistant("요약입니다")),
                        List.of(new ChatAiTranscriptSegment(31L, "민수", 1L, "안건을 시작합니다", 0L, 1_500L))))
        );
    }
}
