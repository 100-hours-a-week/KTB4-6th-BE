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
import java.time.LocalDateTime;
import java.util.ArrayList;
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

    @Test
    @DisplayName("stale PROCESSING 요청은 ACCEPTED로 복구되고 retryCount가 한 번 증가한다")
    void recoversStaleProcessingRequest() {
        // 테스트 목적:
        // 실제 MySQL에서 timeout을 지난 PROCESSING 요청이 조건부 UPDATE 한 번으로
        // ACCEPTED 전이와 실패 횟수 증가를 함께 수행하는지 검증한다.

        // given
        long requestId = createRequest(AiRequestType.SUMMARY, AiRequestStatus.PROCESSING);
        LocalDateTime staleBefore = LocalDateTime.now().minusHours(1);
        makeStale(requestId, staleBefore.minusMinutes(1), 0L);

        // when
        int affectedRows = recover(requestId, staleBefore, 2L);

        // then
        assertThat(affectedRows).isOne();
        assertThat(statusOf(requestId)).isEqualTo(AiRequestStatus.ACCEPTED);
        assertThat(retryCountOf(requestId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("timeout이 지나지 않은 PROCESSING 요청은 복구하지 않는다")
    void keepsFreshProcessingRequest() {
        // 테스트 목적:
        // 정상적으로 AI 응답을 기다리는 PROCESSING 요청이 timeout 이전에는
        // ACCEPTED 또는 FAILED로 변경되지 않는지 검증한다.

        // given
        long requestId = createRequest(AiRequestType.DIARIZATION, AiRequestStatus.PROCESSING);
        LocalDateTime staleBefore = LocalDateTime.now().minusHours(1);

        // when
        int recovered = recover(requestId, staleBefore, 2L);
        int failed = fail(requestId, staleBefore, 2L, 3L);

        // then
        assertThat(recovered).isZero();
        assertThat(failed).isZero();
        assertThat(statusOf(requestId)).isEqualTo(AiRequestStatus.PROCESSING);
        assertThat(retryCountOf(requestId)).isZero();
    }

    @ParameterizedTest(name = "{0} 요청은 stale 복구 대상이 아니다")
    @EnumSource(value = AiRequestStatus.class, names = {"ACCEPTED", "COMPLETED", "FAILED"})
    @DisplayName("PROCESSING이 아닌 요청은 stale 시각이어도 변경하지 않는다")
    void skipsStaleNonProcessingRequest(AiRequestStatus initialStatus) {
        // 테스트 목적:
        // 오래된 timestamp를 가진 요청이라도 현재 상태가 PROCESSING이 아니면
        // stale 복구 조건부 UPDATE가 기존 상태와 retryCount를 보존하는지 검증한다.

        // given
        long requestId = createRequest(AiRequestType.METRIC, initialStatus);
        LocalDateTime staleBefore = LocalDateTime.now().minusHours(1);
        makeStale(requestId, staleBefore.minusMinutes(1), 0L);

        // when
        int recovered = recover(requestId, staleBefore, 2L);
        int failed = fail(requestId, staleBefore, 2L, 3L);

        // then
        assertThat(recovered).isZero();
        assertThat(failed).isZero();
        assertThat(statusOf(requestId)).isEqualTo(initialStatus);
        assertThat(retryCountOf(requestId)).isZero();
    }

    @Test
    @DisplayName("3회째 stale 실패는 retryCount를 증가시키고 FAILED로 전이한다")
    void failsStaleProcessingAtRetryLimit() {
        // 테스트 목적:
        // 기존 재시도 정책과 동일하게 두 번 실패한 PROCESSING 요청의 다음 stale 판정이
        // 세 번째 실패 횟수를 기록하고 최종 실패 처리되는지 검증한다.

        // given
        long requestId = createRequest(AiRequestType.SUMMARY, AiRequestStatus.PROCESSING);
        LocalDateTime staleBefore = LocalDateTime.now().minusHours(1);
        makeStale(requestId, staleBefore.minusMinutes(1), 2L);

        // when
        int recovered = recover(requestId, staleBefore, 2L);
        int failed = fail(requestId, staleBefore, 2L, 3L);

        // then
        assertThat(recovered).isZero();
        assertThat(failed).isOne();
        assertThat(statusOf(requestId)).isEqualTo(AiRequestStatus.FAILED);
        assertThat(retryCountOf(requestId)).isEqualTo(3L);
        assertThat(failureReasonOf(requestId)).isEqualTo(AiFailureReason.AI_CALL_FAILED);
    }

    @Test
    @DisplayName("이미 최대 Retry 횟수인 stale 요청은 횟수를 더 늘리지 않고 FAILED로 전이한다")
    void failsStaleProcessingWithoutExceedingRetryLimit() {
        // 테스트 목적:
        // 비정상 데이터나 이전 실행으로 retryCount가 이미 최대값에 도달한 경우에도
        // stale 실패 처리가 retryCount를 상한보다 증가시키지 않는지 검증한다.

        // given
        long requestId = createRequest(AiRequestType.SUMMARY, AiRequestStatus.PROCESSING);
        LocalDateTime staleBefore = LocalDateTime.now().minusHours(1);
        makeStale(requestId, staleBefore.minusMinutes(1), 3L);

        // when
        int affectedRows = fail(requestId, staleBefore, 2L, 3L);

        // then
        assertThat(affectedRows).isOne();
        assertThat(statusOf(requestId)).isEqualTo(AiRequestStatus.FAILED);
        assertThat(retryCountOf(requestId)).isEqualTo(3L);
        assertThat(failureReasonOf(requestId)).isEqualTo(AiFailureReason.AI_CALL_FAILED);
    }

    @Test
    @DisplayName("20개 트랜잭션이 같은 stale 요청을 복구해도 한 번만 성공한다")
    void onlyOneOfTwentyConcurrentRecoveriesSucceeds() throws Exception {
        // 테스트 목적:
        // 20개 BE 인스턴스에 해당하는 독립 트랜잭션이 동일한 stale 요청을 동시에 복구해도
        // retryCount가 정확히 한 번만 증가하고 중복 복구가 발생하지 않는지 실제 MySQL에서 검증한다.

        // given
        long requestId = createRequest(AiRequestType.DIARIZATION, AiRequestStatus.PROCESSING);
        LocalDateTime staleBefore = LocalDateTime.now().minusHours(1);
        makeStale(requestId, staleBefore.minusMinutes(1), 0L);

        // when
        List<Integer> results = race(20, () -> recover(requestId, staleBefore, 2L));

        // then
        assertThat(results).containsOnly(0, 1);
        assertThat(results.stream().mapToInt(Integer::intValue).sum()).isOne();
        assertThat(statusOf(requestId)).isEqualTo(AiRequestStatus.ACCEPTED);
        assertThat(retryCountOf(requestId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("20개 트랜잭션이 Retry 초과 stale 요청을 처리해도 한 번만 FAILED 전이한다")
    void onlyOneOfTwentyConcurrentFailuresSucceeds() throws Exception {
        // 테스트 목적:
        // 재시도 한도에 도달한 동일 요청을 여러 인스턴스가 동시에 검사해도
        // FAILED 전이와 retryCount 증가가 정확히 한 번만 발생하는지 실제 MySQL에서 검증한다.

        // given
        long requestId = createRequest(AiRequestType.METRIC, AiRequestStatus.PROCESSING);
        LocalDateTime staleBefore = LocalDateTime.now().minusHours(1);
        makeStale(requestId, staleBefore.minusMinutes(1), 2L);

        // when
        List<Integer> results = race(20, () -> fail(requestId, staleBefore, 2L, 3L));

        // then
        assertThat(results).containsOnly(0, 1);
        assertThat(results.stream().mapToInt(Integer::intValue).sum()).isOne();
        assertThat(statusOf(requestId)).isEqualTo(AiRequestStatus.FAILED);
        assertThat(retryCountOf(requestId)).isEqualTo(3L);
    }

    @Test
    @DisplayName("여러 stale PROCESSING 요청을 배치 후보로 조회하고 모두 복구한다")
    void recoversMultipleStaleRequests() {
        // 테스트 목적:
        // Summary, Diarization, Metric stale 요청이 함께 존재할 때
        // 세 요청이 모두 후보로 조회되고 각각 한 번씩 복구되는지 검증한다.

        // given
        LocalDateTime staleBefore = LocalDateTime.now().minusHours(1);
        List<Long> requestIds = List.of(
                createRequest(AiRequestType.SUMMARY, AiRequestStatus.PROCESSING),
                createRequest(AiRequestType.DIARIZATION, AiRequestStatus.PROCESSING),
                createRequest(AiRequestType.METRIC, AiRequestStatus.PROCESSING));
        requestIds.forEach(id -> makeStale(id, staleBefore.minusMinutes(1), 0L));

        // when
        List<AiRequestRecoveryCandidate> candidates = transaction.execute(status ->
                aiRequests.findStaleProcessingCandidates(
                        List.of(AiRequestType.SUMMARY, AiRequestType.DIARIZATION, AiRequestType.METRIC),
                        staleBefore, org.springframework.data.domain.Limit.of(100)));
        int recovered = candidates.stream()
                .mapToInt(candidate -> recover(candidate.id(), staleBefore, 2L))
                .sum();

        // then
        assertThat(candidates).extracting(AiRequestRecoveryCandidate::id).containsExactlyElementsOf(requestIds);
        assertThat(recovered).isEqualTo(3);
        assertThat(requestIds).allSatisfy(id -> {
            assertThat(statusOf(id)).isEqualTo(AiRequestStatus.ACCEPTED);
            assertThat(retryCountOf(id)).isEqualTo(1L);
        });
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

    private int recover(long requestId, LocalDateTime staleBefore, long lastRetryCount) {
        return transaction.execute(status ->
                aiRequests.recoverStaleProcessing(requestId, staleBefore, lastRetryCount));
    }

    private int fail(long requestId, LocalDateTime staleBefore,
                     long lastRetryCount, long maxRetryCount) {
        return transaction.execute(status ->
                aiRequests.failStaleProcessing(requestId, staleBefore, lastRetryCount, maxRetryCount));
    }

    private List<Integer> race(int contenderCount, Callable<Integer> contender) throws Exception {
        CountDownLatch ready = new CountDownLatch(contenderCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(contenderCount);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int index = 0; index < contenderCount; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Stale recovery race did not start");
                    }
                    return contender.call();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private void makeStale(long requestId, LocalDateTime updatedAt, long retryCount) {
        jdbc.update("update ai_requests set updated_at = ?, retry_count = ? where id = ?",
                updatedAt, retryCount, requestId);
    }

    private AiRequestStatus statusOf(long requestId) {
        return AiRequestStatus.valueOf(jdbc.queryForObject(
                "select status from ai_requests where id = ?", String.class, requestId));
    }

    private long retryCountOf(long requestId) {
        return jdbc.queryForObject(
                "select retry_count from ai_requests where id = ?", Long.class, requestId);
    }

    private AiFailureReason failureReasonOf(long requestId) {
        return AiFailureReason.valueOf(jdbc.queryForObject(
                "select failure_reason from ai_requests where id = ?", String.class, requestId));
    }
}
