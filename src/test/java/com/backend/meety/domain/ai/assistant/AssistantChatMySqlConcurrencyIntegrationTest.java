package com.backend.meety.domain.ai.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.entity.AssistantChatConversation;
import com.backend.meety.domain.ai.entity.AssistantChatTurn;
import com.backend.meety.domain.ai.repository.AiRequestRepository;
import com.backend.meety.domain.ai.repository.AssistantChatConversationRepository;
import com.backend.meety.domain.ai.repository.AssistantChatTurnRepository;
import com.backend.meety.domain.meeting.entity.AssistantMeetingCreation;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.repository.AssistantMeetingCreationRepository;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Runs only against an explicitly named disposable local global_chat_test_* MySQL database. */
@SpringJUnitConfig(AssistantChatMySqlConcurrencyIntegrationTest.DatabaseConfig.class)
@EnabledIfEnvironmentVariable(named = "GLOBAL_CHAT_TEST_DB_URL", matches = ".+")
class AssistantChatMySqlConcurrencyIntegrationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses = {
            AiRequestRepository.class, AssistantChatConversationRepository.class,
            AssistantChatTurnRepository.class, TeamMemberRepository.class,
            AssistantMeetingCreationRepository.class
    })
    static class DatabaseConfig {
        @Bean
        DataSource dataSource() {
            String url = System.getenv("GLOBAL_CHAT_TEST_DB_URL");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/global_chat_test_[a-z0-9_]+(?:\\?.*)?")) {
                throw new IllegalArgumentException("A disposable local global_chat_test_* database is required");
            }
            return new DriverManagerDataSource(url, System.getenv().getOrDefault("GLOBAL_CHAT_TEST_DB_USER", "root"),
                    System.getenv().getOrDefault("GLOBAL_CHAT_TEST_DB_PASSWORD", ""));
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan("com.backend.meety.domain", "com.backend.meety.global.entity");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean
        AssistantChatService assistantChatService(TeamMemberRepository members,
                AssistantChatConversationRepository conversations, AssistantChatTurnRepository turns,
                AiRequestRepository requests, AssistantMeetingCreationRepository creations) {
            return new AssistantChatService(members, conversations, turns, requests, creations,
                    new AssistantChatProperties(null, null, null, 20, 20, 50), JsonMapper.builder().build(),
                    jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator(),
                    java.time.Clock.systemUTC());
        }
    }

    @Autowired private AssistantChatService service;
    @Autowired private AiRequestRepository requests;
    @Autowired private AssistantChatConversationRepository conversations;
    @Autowired private AssistantChatTurnRepository turns;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;
    @PersistenceContext private EntityManager entityManager;

    private TransactionTemplate transaction;
    private JdbcTemplate jdbc;
    private long userId;
    private long teamId;

    @BeforeEach
    void prepareDatabase() {
        transaction = new TransactionTemplate(transactionManager);
        jdbc = new JdbcTemplate(dataSource);
        for (String table : List.of("assistant_meeting_creations", "meetings",
                "assistant_chat_turns", "assistant_chat_conversations", "ai_requests",
                "team_members", "teams", "users")) {
            jdbc.update("delete from " + table);
        }
        transaction.executeWithoutResult(status -> {
            User user = User.create();
            Team team = Team.create("전역 채팅 MySQL 동시성 테스트");
            entityManager.persist(user);
            entityManager.persist(team);
            entityManager.persist(TeamMember.createLeader(user, team, "테스트 팀원"));
            entityManager.flush();
            userId = user.getId();
            teamId = team.getId();
        });
    }

    @Test
    @DisplayName("MySQL의 서로 다른 동시 트랜잭션에서 같은 멱등키는 요청과 대화방을 하나만 만든다")
    void concurrentSameKeyCreatesSingleRequestAndConversation() throws Exception {
        var results = runTogether("same-key", "same-key");

        assertThat(results).hasSize(2);
        assertThat(results).extracting(AssistantChatSubmission::requestId).containsOnly(results.getFirst().requestId());
        assertThat(requests.count()).isEqualTo(1);
        assertThat(requests.findAll()).extracting(AiRequest::getRequestType).containsExactly(AiRequestType.GLOBAL_CHAT);
        assertThat(conversations.count()).isEqualTo(1);
        assertThat(turns.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 대화에 서로 다른 동시 질문을 보내면 MySQL 잠금으로 하나만 접수한다")
    void concurrentDifferentQuestionsKeepsOnlyOneProcessingTurn() throws Exception {
        var results = runTogether("key-a", "key-b");

        assertThat(results).hasSize(1);
        assertThat(requests.count()).isEqualTo(1);
        assertThat(conversations.count()).isEqualTo(1);
        assertThat(turns.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("계정 탈퇴 정리는 활성 전역 요청을 종료한 뒤 질문·대화 데이터를 삭제한다")
    void withdrawalCleanupDoesNotLeaveDispatchableOrphanRequest() {
        Long requestId = transaction.execute(status -> {
            Team team = entityManager.find(Team.class, teamId);
            TeamMember member = entityManager.createQuery("select tm from TeamMember tm where tm.user.id = :userId",
                    TeamMember.class).setParameter("userId", userId).getSingleResult();
            AiRequest request = requests.saveAndFlush(AiRequest.create(team, member, "cleanup-key",
                    AiRequestType.GLOBAL_CHAT));
            AssistantChatConversation conversation = conversations.saveAndFlush(
                    AssistantChatConversation.create(team, member));
            turns.saveAndFlush(AssistantChatTurn.create(request, conversation, "삭제 대상 질문"));
            return request.getId();
        });

        transaction.executeWithoutResult(status -> {
            requests.failActiveGlobalChatByUserId(userId);
            turns.deleteAllByUserId(userId);
            conversations.deleteAllByUserId(userId);
        });

        assertThat(requests.findById(requestId).orElseThrow().getStatus()).isEqualTo(AiRequestStatus.FAILED);
        assertThat(turns.count()).isZero();
        assertThat(conversations.count()).isZero();
    }

    @Test
    @DisplayName("생성 전 null에서 승인 기록 저장 후 두 조회 응답에 동일한 회의 ID를 반환한다")
    void createdMeetingIdIsVisibleInBothReadApis() {
        var submission = service.submit(userId, teamId, "created-meeting", "회의 생성");
        assertThat(service.getRequest(userId, submission.requestId()).createdMeetingId()).isNull();

        Long meetingId = persistMeetingCreation(submission.requestId(), false);

        assertThat(service.getRequest(userId, submission.requestId()).createdMeetingId()).isEqualTo(meetingId);
        assertThat(service.getMessages(userId, teamId, null, 20).messages())
                .extracting(value -> value.createdMeetingId()).containsExactly(meetingId);
    }

    @Test
    @DisplayName("생성된 회의를 소프트 삭제해도 생성 이력의 회의 ID는 유지한다")
    void softDeletedMeetingKeepsOriginalCreatedMeetingId() {
        var submission = service.submit(userId, teamId, "deleted-meeting", "회의 생성");
        Long meetingId = persistMeetingCreation(submission.requestId(), true);

        assertThat(service.getRequest(userId, submission.requestId()).createdMeetingId()).isEqualTo(meetingId);
        assertThat(service.getMessages(userId, teamId, null, 20).messages())
                .extracting(value -> value.createdMeetingId()).containsExactly(meetingId);
    }

    private Long persistMeetingCreation(Long requestId, boolean deleted) {
        return transaction.execute(status -> {
            AiRequest request = entityManager.find(AiRequest.class, requestId);
            Meeting meeting = Meeting.create(request.getTeam(), request.getTeamMember(), "테스트 회의",
                    "생성 연결 조회 검증", null, java.time.LocalDateTime.now().plusDays(1), 30);
            entityManager.persist(meeting);
            if (deleted) meeting.softDelete(java.time.LocalDateTime.now());
            entityManager.persist(AssistantMeetingCreation.create(request, meeting, "test-request-hash"));
            entityManager.flush();
            return meeting.getId();
        });
    }

    private List<AssistantChatSubmission> runTogether(String firstKey, String secondKey) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            String firstQuestion = "질문 A";
            String secondQuestion = firstKey.equals(secondKey) ? firstQuestion : "질문 B";
            var first = executor.submit(() -> submitAfterGate(firstKey, firstQuestion, ready, start));
            var second = executor.submit(() -> submitAfterGate(secondKey, secondQuestion, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = new java.util.ArrayList<AssistantChatSubmission>();
            for (var future : List.of(first, second)) {
                try {
                    results.add(future.get(20, TimeUnit.SECONDS));
                } catch (java.util.concurrent.ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    if (!(cause instanceof AssistantChatException chatException)
                            || chatException.getErrorCode() != AssistantChatErrorCode.ACTIVE_REQUEST_EXISTS) {
                        throw exception;
                    }
                }
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private AssistantChatSubmission submitAfterGate(String key, String question,
            CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("concurrency gate timed out");
        return service.submit(userId, teamId, key, question);
    }
}
