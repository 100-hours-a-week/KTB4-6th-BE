package com.backend.meety.domain.ai.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.meety.domain.ai.entity.AiFailureReason;
import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

/** Uses only an explicitly supplied, disposable local ai_request_test_* database. Never loads application.yaml. */
@SpringJUnitConfig(AiRequestAtomicClaimMySqlIntegrationTest.DatabaseConfig.class)
@EnabledIfEnvironmentVariable(named = "AI_REQUEST_TEST_DB_URL", matches = ".+")
class AiRequestAtomicClaimMySqlIntegrationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses = AiRequestRepository.class)
    static class DatabaseConfig {

        @Bean
        DataSource dataSource() {
            String url = System.getenv("AI_REQUEST_TEST_DB_URL");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/ai_request_test_[a-z0-9_]+(?:\\?.*)?")) {
                throw new IllegalArgumentException("A disposable local ai_request_test_* database is required");
            }
            return new DriverManagerDataSource(url,
                    System.getenv().getOrDefault("AI_REQUEST_TEST_DB_USER", "root"),
                    System.getenv().getOrDefault("AI_REQUEST_TEST_DB_PASSWORD", ""));
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
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
    }

    @Autowired
    private AiRequestRepository aiRequests;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private DataSource dataSource;
    @PersistenceContext
    private EntityManager entityManager;

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private long sequence;

    @BeforeEach
    void prepareDatabase() {
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(transactionManager);
        jdbc.update("delete from ai_requests");
        jdbc.update("delete from team_members");
        jdbc.update("delete from teams");
        jdbc.update("delete from users");
    }

    @Test
    @DisplayName("ACCEPTED 요청의 Atomic Claim은 1을 반환하고 PROCESSING으로 전이한다")
    void claimsAcceptedRequest() {
        // 테스트 목적:
        // 실제 MySQL에서 ACCEPTED 조건을 만족하는 요청 하나만 갱신되고
        // 영향 행 수 1과 PROCESSING 상태가 함께 보장되는지 검증한다.

        // given
        long requestId = createRequest(AiRequestType.SUMMARY, AiRequestStatus.ACCEPTED);

        // when
        int affectedRows = claim(requestId);

        // then
        assertThat(affectedRows).isOne();
        assertThat(statusOf(requestId)).isEqualTo(AiRequestStatus.PROCESSING);
    }

    @ParameterizedTest(name = "{0} 요청은 claim할 수 없다")
    @EnumSource(value = AiRequestStatus.class, names = {"PROCESSING", "COMPLETED", "FAILED"})
    @DisplayName("ACCEPTED가 아닌 요청의 Atomic Claim은 0을 반환한다")
    void skipsNonAcceptedRequest(AiRequestStatus initialStatus) {
        // 테스트 목적:
        // PROCESSING, COMPLETED, FAILED 요청은 조건부 UPDATE의 상태 조건에서 제외되어
        // 기존 상태를 유지하고 영향 행 수 0을 반환하는지 검증한다.

        // given
        long requestId = createRequest(AiRequestType.SUMMARY, initialStatus);

        // when
        int affectedRows = claim(requestId);

        // then
        assertThat(affectedRows).isZero();
        assertThat(statusOf(requestId)).isEqualTo(initialStatus);
    }

    @Test
    @DisplayName("같은 요청을 연속 두 번 claim하면 첫 번째만 성공한다")
    void onlyFirstSequentialClaimSucceeds() {
        // 테스트 목적:
        // 동일 ACCEPTED 요청에 조건부 UPDATE를 연속 수행해도
        // 최초 호출만 1을 반환하고 이후 호출은 정상적인 claim miss가 되는지 검증한다.

        // given
        long requestId = createRequest(AiRequestType.SUMMARY, AiRequestStatus.ACCEPTED);

        // when
        int first = claim(requestId);
        int second = claim(requestId);

        // then
        assertThat(first).isOne();
        assertThat(second).isZero();
        assertThat(statusOf(requestId)).isEqualTo(AiRequestStatus.PROCESSING);
    }

    @ParameterizedTest(name = "{0} 동시 선점")
    @EnumSource(value = AiRequestType.class, names = {"SUMMARY", "METRIC", "DIARIZATION"})
    @DisplayName("두 트랜잭션이 동시에 claim해도 도메인별 한 요청은 한 번만 선점된다")
    void onlyOneConcurrentClaimSucceeds(AiRequestType requestType) throws Exception {
        // 테스트 목적:
        // Summary, Diarization, Metric 요청을 두 인스턴스에 해당하는 독립 트랜잭션이 동시에 선점해도
        // 성공 1회와 정상 miss 1회만 발생하는지 실제 MySQL에서 검증한다.

        // given
        long requestId = createRequest(requestType, AiRequestStatus.ACCEPTED);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Callable<Integer> contender = () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Atomic claim race did not start");
                }
                return claim(requestId);
            };
            Future<Integer> first = executor.submit(contender);
            Future<Integer> second = executor.submit(contender);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();

            // when
            start.countDown();
            List<Integer> affectedRows = List.of(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            // then
            assertThat(affectedRows).containsExactlyInAnyOrder(1, 0);
            assertThat(statusOf(requestId)).isEqualTo(AiRequestStatus.PROCESSING);
        } finally {
            executor.shutdownNow();
        }
    }

    private long createRequest(AiRequestType requestType, AiRequestStatus status) {
        return transaction.execute(transactionStatus -> {
            User user = User.create();
            Team team = Team.create("Atomic Claim 테스트");
            entityManager.persist(user);
            entityManager.persist(team);
            TeamMember member = TeamMember.createLeader(user, team, "처리자");
            entityManager.persist(member);
            AiRequest request = AiRequest.create(team, member, "claim-" + requestType + "-" + sequence++, requestType);
            moveTo(request, status);
            entityManager.persist(request);
            entityManager.flush();
            return request.getId();
        });
    }

    private void moveTo(AiRequest request, AiRequestStatus status) {
        if (status == AiRequestStatus.ACCEPTED) {
            return;
        }
        request.markProcessing();
        if (status == AiRequestStatus.COMPLETED) {
            request.markCompleted();
        } else if (status == AiRequestStatus.FAILED) {
            request.markFailed(AiFailureReason.AI_CALL_FAILED);
        }
    }

    private int claim(long requestId) {
        return transaction.execute(status -> aiRequests.claimAccepted(requestId));
    }

    private AiRequestStatus statusOf(long requestId) {
        return AiRequestStatus.valueOf(jdbc.queryForObject(
                "select status from ai_requests where id = ?", String.class, requestId));
    }
}
