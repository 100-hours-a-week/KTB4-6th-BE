package com.backend.meety.domain.ai.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestMetricAiClientTest {

    @Test
    @DisplayName("Metric AI 클라이언트는 POST /v1/metric으로 요청한다")
    void requestMetricPostsToMetricEndpoint() {
        // 테스트 목적:
        // Metric AI RestClient가 기존 AI HTTP 설정을 재사용하면서
        // 실제 외부 서버 없이 계약된 /v1/metric 경로로 JSON 요청을 보내는지 검증한다.

        // given
        RestClient.Builder builder = RestClient.builder().baseUrl("https://ai.example");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestMetricAiClient client = new RestMetricAiClient(builder.build());
        MetricAiRequest request = new MetricAiRequest(
                "1003", 42L, "# 요약", List.of(new MetricAiSegment(101L, 10L, 0L, "발언", 1000L, 3500L)));
        server.expect(requestTo("https://ai.example/v1/metric"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "requestId": "1003",
                          "meetingId": 42,
                          "summary": "# 요약",
                          "segments": [
                            {
                              "segmentId": 101,
                              "speakerId": 10,
                              "sequenceNumber": 0,
                              "content": "발언",
                              "startedAtMs": 1000,
                              "endedAtMs": 3500
                            }
                          ]
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "requestId": "1003",
                          "meetingId": 42,
                          "offTopic": {
                            "durationMs": 2000
                          },
                          "agendas": [
                            {
                              "title": "배포 일정",
                              "durationMs": 2500
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        // when
        MetricAiResponse response = client.requestMetric(request);

        // then
        server.verify();
        assertThat(response.requestId()).isEqualTo("1003");
        assertThat(response.meetingId()).isEqualTo(42L);
        assertThat(response.offTopic().durationMs()).isEqualTo(2000L);
        assertThat(response.agendas().getFirst().durationMs()).isEqualTo(2500L);
    }
}
