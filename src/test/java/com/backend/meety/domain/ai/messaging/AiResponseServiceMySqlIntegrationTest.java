package com.backend.meety.domain.ai.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.backend.meety.domain.ai.entity.AiFailureReason;
import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.entity.PublishStatus;
import com.backend.meety.domain.ai.repository.AiRequestMySqlTestSupport;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ContextConfiguration(classes = AiResponseServiceMySqlIntegrationTest.ResponseConfig.class)
class AiResponseServiceMySqlIntegrationTest extends AiRequestMySqlTestSupport {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 21, 12, 0);
    static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Configuration(proxyBeanMethods = false)
    @Import(AiResponseService.class)
    static class ResponseConfig {

        @Bean
        Clock clock() {
            ZoneId zone = ZoneId.systemDefault();
            return Clock.fixed(NOW.atZone(zone).toInstant(), zone);
        }

        @Bean
        ObjectMapper objectMapper() {
            return OBJECT_MAPPER;
        }

        @Bean
        RecordingHandler summaryHandler() {
            return new RecordingHandler();
        }
    }

    static class RecordingHandler implements AiResultHandler {

        final List<Long> succeeded = new CopyOnWriteArrayList<>();
        final List<JsonNode> results = new CopyOnWriteArrayList<>();
        final List<Long> finalFailures = new CopyOnWriteArrayList<>();
        volatile boolean acceptResult = true;
        volatile long maxRetryCount = AiResponseRetryPolicy.MAX_RETRY_COUNT;

        @Override
        public AiRequestType requestType() {
            return AiRequestType.SUMMARY;
        }

        @Override
        public boolean handleSucceeded(Long aiRequestId, JsonNode result) {
            succeeded.add(aiRequestId);
            results.add(result);
            return acceptResult;
        }

        @Override
        public void handleFinalFailure(Long aiRequestId) {
            finalFailures.add(aiRequestId);
        }

        @Override
        public long maxRetryCount() {
            return maxRetryCount;
        }
    }

    @Autowired
    private AiResponseService responseService;
    @Autowired
    private RecordingHandler handler;

    @BeforeEach
    void clearHandler() {
        handler.succeeded.clear();
        handler.results.clear();
        handler.finalFailures.clear();
        handler.acceptResult = true;
        handler.maxRetryCount = AiResponseRetryPolicy.MAX_RETRY_COUNT;
    }

    @Test
    @DisplayName("처리기가 성공 결과를 받아들이지 않으면 최종 실패로 처리하고 실패 후속 처리를 호출한다")
    void failsWhenHandlerRejectsResult() {
        long requestId = createPublished("token-1", 0);
        handler.acceptResult = false;

        responseService.handle(succeeded(requestId, "token-1"));

        AiRequest request = findRequest(requestId);
        assertThat(request.getStatus()).isEqualTo(AiRequestStatus.FAILED);
        assertThat(request.getFailureReason()).isEqualTo(AiFailureReason.AI_CALL_FAILED);
        assertThat(handler.finalFailures).containsExactly(requestId);
    }

    @Test
    @DisplayName("현재 토큰의 성공 응답은 COMPLETED로 바꾸고 결과를 한 번 반영한다")
    void completesCurrentSuccess() {
        long requestId = createPublished("token-1", 0);

        responseService.handle(succeeded(requestId, "token-1"));

        AiRequest request = findRequest(requestId);
        assertThat(request.getStatus()).isEqualTo(AiRequestStatus.COMPLETED);
        assertThat(request.getProcessingToken()).isNull();
        assertThat(handler.succeeded).containsExactly(requestId);
        assertThat(handler.results.getFirst().get("content").asString()).isEqualTo("요약");
    }

    @Test
    @DisplayName("같은 성공 응답이 다시 와도 결과는 한 번만 반영한다")
    void ignoresRedeliveredSuccess() {
        long requestId = createPublished("token-1", 0);

        responseService.handle(succeeded(requestId, "token-1"));
        responseService.handle(succeeded(requestId, "token-1"));

        assertThat(handler.succeeded).containsExactly(requestId);
    }

    @Test
    @DisplayName("같은 성공 응답이 동시에 와도 결과는 한 번만 반영한다")
    void appliesConcurrentDuplicateSuccessOnce() throws Exception {
        long requestId = createPublished("token-1", 0);

        runConcurrently(8, () -> responseService.handle(succeeded(requestId, "token-1")));

        assertThat(handler.succeeded).containsExactly(requestId);
        assertThat(findRequest(requestId).getStatus()).isEqualTo(AiRequestStatus.COMPLETED);
    }

    @Test
    @DisplayName("이전 토큰의 성공 응답은 반영하지 않는다")
    void ignoresStaleTokenSuccess() {
        long requestId = createPublished("token-2", 1);

        responseService.handle(succeeded(requestId, "token-1"));

        AiRequest request = findRequest(requestId);
        assertThat(request.getStatus()).isEqualTo(AiRequestStatus.ACCEPTED);
        assertThat(request.getProcessingToken()).isEqualTo("token-2");
        assertThat(handler.succeeded).isEmpty();
    }

    @Test
    @DisplayName("이미 COMPLETED·FAILED인 요청에는 응답을 반영하지 않는다")
    void ignoresResponseForFinishedRequest() {
        long completed = createPublished("token-1", 0);
        long failed = createPublished("token-1", 0);
        jdbc.update("update ai_requests set status = 'COMPLETED' where id = ?", completed);
        jdbc.update("update ai_requests set status = 'FAILED' where id = ?", failed);

        responseService.handle(succeeded(completed, "token-1"));
        responseService.handle(failedResponse(failed, "token-1"));

        assertThat(findRequest(completed).getStatus()).isEqualTo(AiRequestStatus.COMPLETED);
        assertThat(findRequest(failed).getRetryCount()).isZero();
        assertThat(handler.succeeded).isEmpty();
        assertThat(handler.finalFailures).isEmpty();
    }

    @Test
    @DisplayName("첫 실패는 새 토큰과 메시지로 30초 뒤 재발행을 예약한다")
    void schedulesFirstRetryAfter30Seconds() {
        long requestId = createPublished("token-1", 0);

        responseService.handle(failedResponse(requestId, "token-1"));

        AiRequest request = findRequest(requestId);
        assertThat(request.getStatus()).isEqualTo(AiRequestStatus.ACCEPTED);
        assertThat(request.getRetryCount()).isEqualTo(1L);
        assertThat(request.getProcessingToken()).isNotEqualTo("token-1").isNotBlank();
        assertThat(request.getPublishStatus()).isEqualTo(PublishStatus.UNPUBLISHED);
        assertThat(request.getNextPublishAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(request.getPublishStartedAt()).isNull();
        assertThat(request.getPublishedAt()).isNull();
        JsonNode payload = OBJECT_MAPPER.readTree(request.getMessagePayload());
        assertThat(payload.get("processingToken").asString()).isEqualTo(request.getProcessingToken());
        assertThat(payload.get("input").get("meetingId").asLong()).isEqualTo(7L);
        assertThat(handler.finalFailures).isEmpty();
    }

    @Test
    @DisplayName("두 번째 실패는 120초 뒤 재발행을 예약한다")
    void schedulesSecondRetryAfter120Seconds() {
        long requestId = createPublished("token-2", 1);

        responseService.handle(failedResponse(requestId, "token-2"));

        AiRequest request = findRequest(requestId);
        assertThat(request.getRetryCount()).isEqualTo(2L);
        assertThat(request.getNextPublishAt()).isEqualTo(NOW.plusSeconds(120));
        assertThat(request.getPublishStatus()).isEqualTo(PublishStatus.UNPUBLISHED);
    }

    @Test
    @DisplayName("세 번째 실패는 최종 실패로 처리하고 실패 후속 처리를 한 번 호출한다")
    void failsOnThirdFailure() {
        long requestId = createPublished("token-3", 2);

        responseService.handle(failedResponse(requestId, "token-3"));

        AiRequest request = findRequest(requestId);
        assertThat(request.getStatus()).isEqualTo(AiRequestStatus.FAILED);
        assertThat(request.getFailureReason()).isEqualTo(AiFailureReason.AI_CALL_FAILED);
        assertThat(request.getRetryCount()).isEqualTo(3L);
        assertThat(request.getProcessingToken()).isNull();
        assertThat(handler.finalFailures).containsExactly(requestId);
    }

    @Test
    @DisplayName("처리기의 최대 재시도 횟수가 1이면 첫 실패를 재시도 없이 최종 실패로 처리한다")
    void failsWithoutRetryWhenHandlerAllowsOneAttempt() {
        handler.maxRetryCount = 1;
        long requestId = createPublished("token-1", 0);

        responseService.handle(failedResponse(requestId, "token-1"));

        AiRequest request = findRequest(requestId);
        assertThat(request.getStatus()).isEqualTo(AiRequestStatus.FAILED);
        assertThat(request.getRetryCount()).isEqualTo(1L);
        assertThat(handler.finalFailures).containsExactly(requestId);
    }

    @Test
    @DisplayName("재시도를 예약한 뒤 도착한 이전 토큰의 실패 응답은 다시 예약하지 않는다")
    void ignoresStaleFailureAfterRetryScheduled() {
        long requestId = createPublished("token-1", 0);
        responseService.handle(failedResponse(requestId, "token-1"));
        String nextToken = findRequest(requestId).getProcessingToken();

        responseService.handle(failedResponse(requestId, "token-1"));

        AiRequest request = findRequest(requestId);
        assertThat(request.getRetryCount()).isEqualTo(1L);
        assertThat(request.getProcessingToken()).isEqualTo(nextToken);
    }

    @Test
    @DisplayName("같은 최종 실패 응답이 동시에 와도 실패 후속 처리는 한 번만 호출한다")
    void appliesConcurrentFinalFailureOnce() throws Exception {
        long requestId = createPublished("token-3", 2);

        runConcurrently(8, () -> responseService.handle(failedResponse(requestId, "token-3")));

        assertThat(handler.finalFailures).containsExactly(requestId);
        assertThat(findRequest(requestId).getRetryCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("같은 첫 실패 응답이 동시에 와도 재시도는 한 번만 예약한다")
    void schedulesConcurrentRetryOnce() throws Exception {
        long requestId = createPublished("token-1", 0);

        runConcurrently(8, () -> responseService.handle(failedResponse(requestId, "token-1")));

        assertThat(findRequest(requestId).getRetryCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("결과 처리기가 없는 작업 유형의 응답은 반영하지 않는다")
    void rejectsResponseWithoutHandler() {
        long requestId = createPublished("token-1", 0);
        AiResponseMessage metric = new AiResponseMessage(1, requestId, "token-1", AiRequestType.METRIC,
                AiResponseEventType.SUCCEEDED, OBJECT_MAPPER.readTree("{}"), null);

        assertThatThrownBy(() -> responseService.handle(metric)).isInstanceOf(IllegalStateException.class);

        assertThat(findRequest(requestId).getStatus()).isEqualTo(AiRequestStatus.ACCEPTED);
    }

    private long createPublished(String processingToken, long retryCount) {
        long requestId = createRequest(AiRequestType.SUMMARY);
        jdbc.update("""
                        update ai_requests
                        set publish_status = 'PUBLISHED', processing_token = ?, retry_count = ?,
                            message_payload = ?, publish_routing_key = 'summary',
                            next_publish_at = ?, publish_started_at = ?, published_at = ?
                        where id = ?
                        """,
                processingToken, retryCount,
                "{\"schemaVersion\":1,\"aiRequestId\":" + requestId + ",\"processingToken\":\"" + processingToken
                        + "\",\"requestType\":\"SUMMARY\",\"input\":{\"meetingId\":7}}",
                NOW.minusMinutes(1), NOW.minusSeconds(50), NOW.minusSeconds(49), requestId);
        return requestId;
    }

    private AiResponseMessage succeeded(long requestId, String processingToken) {
        return new AiResponseMessage(1, requestId, processingToken, AiRequestType.SUMMARY,
                AiResponseEventType.SUCCEEDED, OBJECT_MAPPER.readTree("{\"content\":\"요약\"}"), null);
    }

    private AiResponseMessage failedResponse(long requestId, String processingToken) {
        return new AiResponseMessage(1, requestId, processingToken, AiRequestType.SUMMARY,
                AiResponseEventType.FAILED, null, new AiResponseError("generation_failed"));
    }

    private void runConcurrently(int threads, Runnable task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    task.run();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
