package com.backend.meety.domain.ai.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestSttPrewarmAiClientTest {

    @Test
    @DisplayName("STT prewarm은 회의 ID 숫자와 KST offset이 있는 예정 시각을 JSON으로 전송한다")
    void sendsPrewarmContract() {
        // 테스트 목적:
        // 기존 AI base URL에 내부 prewarm 경로를 붙이고 15시 예정 시각을 15시 +09:00으로 전송하는지 검증한다.
        // given
        RestClient.Builder builder = RestClient.builder().baseUrl("https://ai.example");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ai.example/internal/stt/prewarm"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"meetingId":123,"startsAt":"2026-10-22T15:00:00+09:00"}
                        """))
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"accepted\"}"));
        var client = new RestSttPrewarmAiClient(builder.build());

        // when
        var status = client.requestPrewarm(new SttPrewarmRequest(123L,
                OffsetDateTime.parse("2026-10-22T15:00:00+09:00")));

        // then
        assertThat(status.value()).isEqualTo(202);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 422, 500})
    @DisplayName("AI 오류 HTTP status는 scheduler가 회의별로 처리할 수 있도록 반환한다")
    void returnsErrorStatusWithoutAbortingClient(int httpStatus) {
        // 테스트 목적:
        // 인증·입력·서버 실패 HTTP 응답을 구분해 다음 회의 처리로 이어갈 수 있는지 검증한다.
        // given
        RestClient.Builder builder = RestClient.builder().baseUrl("https://ai.example");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ai.example/internal/stt/prewarm"))
                .andRespond(withStatus(HttpStatus.valueOf(httpStatus)));
        var client = new RestSttPrewarmAiClient(builder.build());

        // when
        var status = client.requestPrewarm(new SttPrewarmRequest(123L,
                OffsetDateTime.parse("2026-10-22T15:00:00+09:00")));

        // then
        assertThat(status.value()).isEqualTo(httpStatus);
        server.verify();
    }
}
