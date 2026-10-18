package com.backend.meety.domain.ai.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class MetricAiDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Metric 요청 DTO는 AI 계약 JSON으로 직렬화된다")
    void serializeMetricRequest() {
        // 테스트 목적:
        // BE가 Metric AI에 전달하는 requestId, meetingId, summary, segments 필드와
        // 밀리초 시간 값이 계약된 JSON 이름과 숫자 타입으로 직렬화되는지 검증한다.

        // given
        MetricAiRequest request = new MetricAiRequest(
                "1003",
                42L,
                "# 회의 요약\n\n- 금요일까지 배포한다.",
                List.of(new MetricAiSegment(101L, 10L, 0L, "금요일까지 배포하겠습니다.", 1000L, 3500L))
        );

        // when
        JsonNode root = objectMapper.readTree(objectMapper.writeValueAsString(request));

        // then
        assertThat(root.path("requestId").asText()).isEqualTo("1003");
        assertThat(root.path("meetingId").asLong()).isEqualTo(42L);
        assertThat(root.path("summary").asText()).contains("금요일까지 배포한다.");
        assertThat(root.path("segments").get(0).path("segmentId").asLong()).isEqualTo(101L);
        assertThat(root.path("segments").get(0).path("speakerId").asLong()).isEqualTo(10L);
        assertThat(root.path("segments").get(0).path("sequenceNumber").asLong()).isZero();
        assertThat(root.path("segments").get(0).path("startedAtMs").asLong()).isEqualTo(1000L);
        assertThat(root.path("segments").get(0).path("endedAtMs").asLong()).isEqualTo(3500L);
    }

    @Test
    @DisplayName("Metric 응답 DTO는 null과 0을 구분해 역직렬화된다")
    void deserializeMetricResponse() {
        // 테스트 목적:
        // AI Metric 응답에서 offTopic.durationMs와 agendas가 null이거나 0인 경우
        // BE DTO가 값을 보정하지 않고 그대로 복원하는지 검증한다.

        // given
        String json = """
                {
                  "requestId": "1003",
                  "meetingId": 42,
                  "offTopic": {
                    "durationMs": 0
                  },
                  "agendas": [
                    {
                      "title": "배포 일정",
                      "durationMs": null
                    }
                  ]
                }
                """;

        // when
        MetricAiResponse response = objectMapper.readValue(json, MetricAiResponse.class);

        // then
        assertThat(response.requestId()).isEqualTo("1003");
        assertThat(response.meetingId()).isEqualTo(42L);
        assertThat(response.offTopic().durationMs()).isZero();
        assertThat(response.agendas()).hasSize(1);
        assertThat(response.agendas().getFirst().title()).isEqualTo("배포 일정");
        assertThat(response.agendas().getFirst().durationMs()).isNull();
    }

    @Test
    @DisplayName("Metric 응답 DTO는 agendas null을 보존한다")
    void deserializeNullAgendas() {
        // 테스트 목적:
        // 전사가 없거나 분류 가능한 안건이 없어 AI가 agendas를 null로 응답할 때
        // 빈 배열로 바꾸지 않고 null로 복원하는지 검증한다.

        // given
        String json = """
                {
                  "requestId": "1003",
                  "meetingId": 42,
                  "offTopic": null,
                  "agendas": null
                }
                """;

        // when
        MetricAiResponse response = objectMapper.readValue(json, MetricAiResponse.class);

        // then
        assertThat(response.offTopic()).isNull();
        assertThat(response.agendas()).isNull();
    }
}
