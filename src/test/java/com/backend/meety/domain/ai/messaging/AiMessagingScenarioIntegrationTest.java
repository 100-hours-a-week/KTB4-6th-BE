package com.backend.meety.domain.ai.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.ai.repository.AiRequestRepository;
import com.backend.meety.domain.credit.CreditPolicy;
import com.backend.meety.domain.credit.entity.TeamCredit;
import com.backend.meety.domain.credit.repository.TeamCreditRepository;
import com.backend.meety.domain.meeting.dto.SummaryCreateResponse;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.meeting.service.MeetingSummaryService;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.domain.team.repository.TeamRepository;
import com.backend.meety.domain.transcript.entity.TranscriptSegment;
import com.backend.meety.domain.transcript.repository.TranscriptSegmentRepository;
import com.backend.meety.domain.user.entity.User;
import com.backend.meety.domain.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class AiMessagingScenarioIntegrationTest {

    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management");

    static {
        MYSQL.start();
        RABBIT.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("read-datasource.url", MYSQL::getJdbcUrl);
        registry.add("read-datasource.username", MYSQL::getUsername);
        registry.add("read-datasource.password", MYSQL::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("spring.rabbitmq.virtual-host", () -> "/");
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicBoolean dropConnectionAfterNextCommit = new AtomicBoolean();
    private final AtomicInteger handledResponses = new AtomicInteger();

    @MockitoSpyBean
    private AiResponseService aiResponseService;
    @Autowired
    private CachingConnectionFactory connectionFactory;

    @Autowired
    private MeetingSummaryService meetingSummaryService;
    @Autowired
    private AiRequestRepository aiRequestRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private TeamMemberRepository teamMemberRepository;
    @Autowired
    private TeamCreditRepository teamCreditRepository;
    @Autowired
    private MeetingRepository meetingRepository;
    @Autowired
    private TranscriptSegmentRepository transcriptSegmentRepository;
    @Autowired
    private CreditPolicy creditPolicy;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RabbitAdmin rabbitAdmin;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transaction;

    private Long userId;
    private Long teamId;
    private Long meetingId;
    private long initialBalance;

    @BeforeEach
    void setUp() {
        transaction.executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now();
            User user = userRepository.save(User.create());
            Team team = teamRepository.save(Team.create("시나리오 팀"));
            TeamMember leader = teamMemberRepository.save(TeamMember.createLeader(user, team, "리더"));
            TeamCredit credit = teamCreditRepository.save(
                    TeamCredit.create(team, creditPolicy.maxBalance(), creditPolicy.maxBalance()));
            Meeting meeting = Meeting.create(team, leader, "주간 회의", "배포 일정 결정", null, now, 30);
            meeting.start(now);
            meeting.complete(now);
            meetingRepository.save(meeting);
            transcriptSegmentRepository.save(TranscriptSegment.createFinal(
                    meeting, UUID.randomUUID().toString(), 1L, "금요일에 배포합니다.", 0L, 1_000L, now, null));
            userId = user.getId();
            teamId = team.getId();
            meetingId = meeting.getId();
            initialBalance = credit.getBalance();
        });
    }

    @BeforeEach
    void interceptResponseHandling() {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            handledResponses.incrementAndGet();
            if (dropConnectionAfterNextCommit.compareAndSet(true, false)) {
                connectionFactory.resetConnection();
            }
            return null;
        }).when(aiResponseService).handle(any());
    }

    @AfterEach
    void purgeQueues() {
        rabbitAdmin.purgeQueue(AiRabbitTopology.SUMMARY_REQUEST_QUEUE);
        rabbitAdmin.purgeQueue(AiRabbitTopology.METRIC_REQUEST_QUEUE);
        rabbitAdmin.purgeQueue(AiRabbitTopology.RESPONSE_DLQ);
    }

    @Test
    @DisplayName("요약 등록부터 발행·성공 응답·결과 저장까지 이어지고, 같은 응답이 다시 와도 결과와 지표 요청은 한 번만 생긴다")
    void completesSummaryOnceThroughBroker() {
        Long aiRequestId = requestSummary();
        JsonNode request = receiveRequest(AiRabbitTopology.SUMMARY_REQUEST_QUEUE);
        assertThat(request.get("aiRequestId").asLong()).isEqualTo(aiRequestId);
        assertThat(request.get("input").get("meetingId").asLong()).isEqualTo(meetingId);

        String response = succeeded(aiRequestId, request.get("processingToken").asString());
        sendResponse(response);
        await(() -> statusOf(aiRequestId) == AiRequestStatus.COMPLETED);
        sendResponse(response);
        awaitPreviousResponsesHandled();

        assertThat(jdbc.queryForObject(
                "select content from meeting_summaries where ai_request_id = ?", String.class, aiRequestId))
                .isEqualTo("금요일 배포로 결정했습니다.");
        assertThat(countMetricRequests()).isEqualTo(1);
        assertThat(receiveRequest(AiRabbitTopology.METRIC_REQUEST_QUEUE).get("requestType").asString())
                .isEqualTo("METRIC");
    }

    @Test
    @DisplayName("DB 커밋 후 ACK 전에 연결이 끊기면 브로커가 응답을 다시 보내고, 결과와 지표 요청은 한 번만 생긴다")
    void redeliversResponseWhenConnectionDropsBeforeAck() {
        Long aiRequestId = requestSummary();
        String processingToken = receiveRequest(AiRabbitTopology.SUMMARY_REQUEST_QUEUE)
                .get("processingToken").asString();
        dropConnectionAfterNextCommit.set(true);

        sendResponse(succeeded(aiRequestId, processingToken));
        await(() -> handledResponses.get() >= 2);
        awaitPreviousResponsesHandled();

        assertThat(dropConnectionAfterNextCommit).isFalse();
        assertThat(statusOf(aiRequestId)).isEqualTo(AiRequestStatus.COMPLETED);
        assertThat(countMetricRequests()).isEqualTo(1);
    }

    @Test
    @DisplayName("마지막 시도의 실패 응답이 두 번 와도 최종 실패와 크레딧 복구는 한 번만 일어난다")
    void restoresCreditOnceOnFinalFailure() {
        Long aiRequestId = requestSummary();
        String processingToken = receiveRequest(AiRabbitTopology.SUMMARY_REQUEST_QUEUE)
                .get("processingToken").asString();
        assertThat(balance()).isEqualTo(initialBalance - creditPolicy.summaryRegenerateCost());
        jdbc.update("update ai_requests set retry_count = ? where id = ?",
                AiResponseRetryPolicy.MAX_RETRY_COUNT - 1, aiRequestId);

        String response = failed(aiRequestId, processingToken);
        sendResponse(response);
        sendResponse(response);
        awaitPreviousResponsesHandled();
        assertThat(statusOf(aiRequestId)).isEqualTo(AiRequestStatus.FAILED);

        assertThat(balance()).isEqualTo(initialBalance);
        assertThat(jdbc.queryForObject(
                "select count(*) from credit_ledgers where idempotency_key = ?", Long.class,
                "RESTORE:AI_SUMMARY:" + aiRequestId)).isEqualTo(1L);
    }

    private Long requestSummary() {
        SummaryCreateResponse created = meetingSummaryService.requestSummary(
                userId, meetingId, UUID.randomUUID().toString(), null);
        return jdbc.queryForObject("select ai_request_id from meeting_summaries where id = ?",
                Long.class, created.summaryId());
    }

    private JsonNode receiveRequest(String queue) {
        Message message = rabbitTemplate.receive(queue, 10_000);
        assertThat(message).as("%s 메시지", queue).isNotNull();
        return objectMapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
    }

    private String succeeded(Long aiRequestId, String processingToken) {
        return """
                {"schemaVersion":1,"aiRequestId":%d,"processingToken":"%s","requestType":"SUMMARY",
                 "eventType":"SUCCEEDED","result":{"content":"금요일 배포로 결정했습니다."}}
                """.formatted(aiRequestId, processingToken);
    }

    private String failed(Long aiRequestId, String processingToken) {
        return """
                {"schemaVersion":1,"aiRequestId":%d,"processingToken":"%s","requestType":"SUMMARY",
                 "eventType":"FAILED","error":{"code":"generation_failed"}}
                """.formatted(aiRequestId, processingToken);
    }

    private void sendResponse(String body) {
        rabbitTemplate.convertAndSend(AiRabbitTopology.RESPONSE_EXCHANGE, AiRabbitTopology.RESPONSE_ROUTING_KEY, body);
    }

    private AiRequestStatus statusOf(Long aiRequestId) {
        return aiRequestRepository.findById(aiRequestId).map(AiRequest::getStatus).orElseThrow();
    }

    private long countMetricRequests() {
        return jdbc.queryForObject(
                "select count(*) from ai_requests where team_id = ? and request_type = 'METRIC'", Long.class, teamId);
    }

    private long balance() {
        return jdbc.queryForObject("select balance from team_credits where team_id = ?", Long.class, teamId);
    }

    private void awaitPreviousResponsesHandled() {
        sendResponse("{}");
        await(() -> rabbitAdmin.getQueueInfo(AiRabbitTopology.RESPONSE_DLQ).getMessageCount() == 1);
    }

    private void await(BooleanSupplier condition) {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("condition was not met within 10 seconds");
    }
}
