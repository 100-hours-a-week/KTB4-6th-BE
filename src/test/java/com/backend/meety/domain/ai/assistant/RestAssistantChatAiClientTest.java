package com.backend.meety.domain.ai.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

class RestAssistantChatAiClientTest {

    @Test
    void streamsProgressAndReturnsPersistableCompletion() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://ai.test/v1/assistant/chat"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"aiRequestId":42,"teamId":7,"question":"분석해줘","recentMessages":[]}
                        """))
                .andRespond(withSuccess("""
                        event: status
                        data: {"phase":"SEARCHING_MEETINGS"}

                        event: delta
                        data: {"text":"결과입니다."}

                        : ping

                        event: completed
                        data: {"aiRequestId":42,"answer":"결과입니다.","referencedMeetingIds":[3],"meetingDraft":{"title":"주간 회의"}}

                        """, MediaType.TEXT_EVENT_STREAM));
        var client = new RestAssistantChatAiClient(builder.build(), new ObjectMapper(), properties());
        List<String> names = new ArrayList<>();

        AssistantChatAiResult result = client.stream(
                new AssistantChatAiRequest(42L, 7L, "분석해줘", List.of()),
                event -> names.add(event.name()));

        assertThat(names).containsExactly("status", "delta", "ping");
        assertThat(result.answer()).isEqualTo("결과입니다.");
        assertThat(result.referencedMeetingIds()).containsExactly(3L);
        assertThat(result.meetingDraft().path("title").asText()).isEqualTo("주간 회의");
        server.verify();
    }

    @Test
    void doesNotTreatStreamCloseWithoutTerminalEventAsSuccess() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://ai.test/v1/assistant/chat"))
                .andRespond(withSuccess("event: delta\ndata: {\"text\":\"부분 응답\"}\n\n",
                        MediaType.TEXT_EVENT_STREAM));
        var client = new RestAssistantChatAiClient(builder.build(), new ObjectMapper(), properties());

        assertThatThrownBy(() -> client.stream(
                new AssistantChatAiRequest(42L, 7L, "질문", List.of()), ignored -> {}))
                .isInstanceOf(AssistantChatAiException.class)
                .hasMessageContaining("without a terminal event");
        server.verify();
    }

    @Test
    void rejectsCompletionForDifferentRequest() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://ai.test/v1/assistant/chat"))
                .andRespond(withSuccess("event: completed\ndata: {\"aiRequestId\":41,\"answer\":\"잘못된 응답\"}\n\n",
                        MediaType.TEXT_EVENT_STREAM));
        var client = new RestAssistantChatAiClient(builder.build(), new ObjectMapper(), properties());

        assertThatThrownBy(() -> client.stream(
                new AssistantChatAiRequest(42L, 7L, "질문", List.of()), ignored -> {}))
                .isInstanceOf(AssistantChatAiException.class)
                .hasMessageContaining("request ID mismatch");
        server.verify();
    }

    @Test
    void failedEventIsNotReturnedAsACompletedAnswer() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://ai.test/v1/assistant/chat"))
                .andRespond(withSuccess("event: failed\ndata: {\"aiRequestId\":42,\"code\":\"timeout\"}\n\n",
                        MediaType.TEXT_EVENT_STREAM));
        var client = new RestAssistantChatAiClient(builder.build(), new ObjectMapper(), properties());

        assertThatThrownBy(() -> client.stream(
                new AssistantChatAiRequest(42L, 7L, "질문", List.of()), ignored -> {}))
                .isInstanceOf(AssistantChatAiException.class)
                .hasMessageContaining("AI request failed");
        server.verify();
    }

    @Test
    void rejects422And503WithoutTreatingThemAsSuccessfulStreams() {
        for (HttpStatus status : List.of(HttpStatus.UNPROCESSABLE_ENTITY, HttpStatus.SERVICE_UNAVAILABLE)) {
            RestClient.Builder builder = RestClient.builder().baseUrl("http://ai.test");
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("http://ai.test/v1/assistant/chat"))
                    .andRespond(MockRestResponseCreators.withStatus(status));
            var client = new RestAssistantChatAiClient(builder.build(), new ObjectMapper(), properties());

            assertThatThrownBy(() -> client.stream(
                    new AssistantChatAiRequest(42L, 7L, "질문", List.of()), ignored -> {}))
                    .isInstanceOf(AssistantChatAiException.class)
                    .hasMessageContaining("HTTP " + status.value());
            server.verify();
        }
    }

    @Test
    void firstCompletedEventWinsAndLateTerminalEventsAreIgnored() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://ai.test/v1/assistant/chat"))
                .andRespond(withSuccess("event: completed\ndata: {\"aiRequestId\":42,\"answer\":\"첫 결과\"}\n\n"
                                + "event: completed\ndata: {\"aiRequestId\":42,\"answer\":\"늦은 결과\"}\n\n",
                        MediaType.TEXT_EVENT_STREAM));
        var client = new RestAssistantChatAiClient(builder.build(), new ObjectMapper(), properties());

        AssistantChatAiResult result = client.stream(
                new AssistantChatAiRequest(42L, 7L, "질문", List.of()), ignored -> {});

        assertThat(result.answer()).isEqualTo("첫 결과");
        server.verify();
    }

    private AssistantChatProperties properties() {
        return new AssistantChatProperties(Duration.ofSeconds(180), Duration.ofSeconds(200),
                Duration.ofSeconds(30), 20, 20, 50);
    }
}
