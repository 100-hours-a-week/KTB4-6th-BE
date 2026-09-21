package com.backend.meety.domain.recording;

import static com.backend.meety.domain.recording.RecordingFixtures.CLOCK;
import static com.backend.meety.domain.recording.RecordingFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.backend.meety.domain.credit.entity.TeamCredit;
import com.backend.meety.domain.credit.repository.CreditLedgerRepository;
import com.backend.meety.domain.credit.repository.TeamCreditRepository;
import com.backend.meety.domain.meeting.dto.MeetingUpdateRequest;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingParticipant;
import com.backend.meety.domain.meeting.repository.MeetingParticipantRepository;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.meeting.service.MeetingService;
import com.backend.meety.domain.recording.dto.RecordingSessionResponse;
import com.backend.meety.domain.recording.entity.RecordingSessionStatus;
import com.backend.meety.domain.recording.exception.RecordingErrorCode;
import com.backend.meety.domain.recording.repository.RecordingSessionRepository;
import com.backend.meety.domain.recording.service.RecordingService;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.domain.team.repository.TeamRepository;
import com.backend.meety.domain.user.entity.User;
import com.backend.meety.global.exception.BusinessException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
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

/** Uses only an explicitly supplied, disposable local recording_test_* database. Never loads application.yaml. */
@SpringJUnitConfig(RecordingMySqlIntegrationTest.DatabaseConfig.class)
@EnabledIfEnvironmentVariable(named = "RECORDING_TEST_DB_URL", matches = ".+")
class RecordingMySqlIntegrationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses = {
            MeetingRepository.class, TeamRepository.class, RecordingSessionRepository.class, TeamCreditRepository.class
    })
    static class DatabaseConfig {

        @Bean
        DataSource dataSource() {
            String url = System.getenv("RECORDING_TEST_DB_URL");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/recording_test_[a-z0-9_]+(?:\\?.*)?")) {
                throw new IllegalArgumentException("A disposable local recording_test_* database is required");
            }
            return new DriverManagerDataSource(url,
                    System.getenv().getOrDefault("RECORDING_TEST_DB_USER", "root"),
                    System.getenv().getOrDefault("RECORDING_TEST_DB_PASSWORD", ""));
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan("com.backend.meety.domain", "com.backend.meety.global.entity");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of(
                    "hibernate.hbm2ddl.auto", "create-drop",
                    "hibernate.generate_statistics", "true"
            ));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean
        RecordingService recordingService(MeetingRepository meetings, TeamMemberRepository members,
                MeetingParticipantRepository participants, RecordingSessionRepository recordings,
                TeamCreditRepository credits, CreditLedgerRepository ledgers, ApplicationEventPublisher publisher) {
            return new RecordingService(meetings, members, participants, recordings, credits, ledgers, CLOCK,
                    publisher);
        }

        @Bean
        MeetingService meetingService(MeetingRepository meetings, TeamRepository teams, TeamMemberRepository members) {
            return new MeetingService(meetings, teams, members, CLOCK);
        }
    }

    @Autowired
    private RecordingService recordings;
    @Autowired
    private MeetingService meetings;
    @Autowired
    private TeamCreditRepository credits;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @PersistenceContext
    private EntityManager entityManager;

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private long userId;
    private long teamId;
    private long memberId;
    private long meetingId;

    @BeforeEach
    void prepareDatabase() {
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(transactionManager);
        for (String table : List.of("credit_ledgers", "recording_sessions", "meeting_participants", "meetings",
                "team_credits", "team_members", "teams", "users")) {
            jdbc.update("delete from " + table);
        }
        transaction.executeWithoutResult(status -> {
            User user = User.create();
            Team team = Team.create("통합 테스트");
            entityManager.persist(user);
            entityManager.persist(team);
            TeamMember member = TeamMember.createLeader(user, team, "시작자");
            entityManager.persist(member);
            Meeting meeting = Meeting.create(team, member, "회의", "테스트", null, NOW, 30);
            entityManager.persist(meeting);
            entityManager.persist(MeetingParticipant.create(meeting, member));
            entityManager.persist(RecordingFixtures.credit(team, 20L));
            userId = user.getId();
            teamId = team.getId();
            memberId = member.getId();
            meetingId = meeting.getId();
        });
    }

    @Test
    void startCommitsSessionCreditLedgerAndMeetingTogether() {
        RecordingSessionResponse result = recordings.start(userId, meetingId);
        assertThat(result.status()).isEqualTo(RecordingSessionStatus.RECORDING);
        assertThat(balance()).isZero();
        assertThat(count("recording_sessions")).isEqualTo(1);
        assertThat(count("credit_ledgers")).isEqualTo(1);
        assertThat(meetingStatus()).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("select started_at from meetings where id = ?", LocalDateTime.class, meetingId))
                .isEqualTo(result.startedAt());
        assertThat(jdbc.queryForObject("select balance_after from credit_ledgers", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select amount from credit_ledgers", Long.class)).isEqualTo(20L);
        assertThat(jdbc.queryForObject("select idempotency_key from credit_ledgers", String.class))
                .isEqualTo("recording:start:" + result.recordingSessionId());
    }

    @Test
    void queriesStayBoundedWithoutLoadingOwnerAssociations() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        long sessionId = recordings.start(userId, meetingId).recordingSessionId();
        assertThat(statistics.getPrepareStatementCount()).as("start SQL count").isEqualTo(9);
        statistics.clear();
        recordings.getActive(userId, meetingId);
        assertThat(statistics.getPrepareStatementCount()).as("GET SQL count").isEqualTo(3);
        statistics.clear();
        recordings.updateStatus(userId, sessionId, RecordingSessionStatus.PAUSED);
        assertThat(statistics.getPrepareStatementCount()).as("pause SQL count").isEqualTo(5);
        statistics.clear();
        recordings.updateStatus(userId, sessionId, RecordingSessionStatus.RECORDING);
        assertThat(statistics.getPrepareStatementCount()).as("resume SQL count").isEqualTo(5);
        statistics.clear();
        recordings.updateStatus(userId, sessionId, RecordingSessionStatus.COMPLETED);
        assertThat(statistics.getPrepareStatementCount()).as("complete SQL count").isEqualTo(6);
    }

    @Test
    void meetingCompleteFailureAlsoRollsBackSessionCompletion() {
        long sessionId = recordings.start(userId, meetingId).recordingSessionId();
        jdbc.execute("create trigger reject_recording_test_complete before update on meetings"
                + " for each row signal sqlstate '45000' set message_text = 'injected meeting complete failure'");
        try {
            assertThatThrownBy(() -> recordings.updateStatus(userId, sessionId, RecordingSessionStatus.COMPLETED))
                    .isInstanceOf(DataAccessException.class);
            assertThat(meetingStatus()).isEqualTo("IN_PROGRESS");
            assertThat(jdbc.queryForObject("select status from recording_sessions where id = ?", String.class, sessionId))
                    .isEqualTo("RECORDING");
            assertThat(jdbc.queryForObject("select ended_at from recording_sessions where id = ?", LocalDateTime.class, sessionId))
                    .isNull();
        } finally {
            jdbc.execute("drop trigger reject_recording_test_complete");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"recording_sessions", "credit_ledgers"})
    void failedInsertRollsBackEveryTable(String failingTable) {
        jdbc.execute("create trigger reject_recording_test_insert before insert on " + failingTable
                + " for each row signal sqlstate '45000' set message_text = 'injected recording test failure'");
        try {
            assertThatThrownBy(() -> recordings.start(userId, meetingId)).isInstanceOf(DataAccessException.class);
            assertThat(count("recording_sessions")).isZero();
            assertThat(count("credit_ledgers")).isZero();
            assertThat(balance()).isEqualTo(20);
            assertThat(meetingStatus()).isEqualTo("WAITING");
            assertThat(jdbc.queryForObject("select started_at from meetings where id = ?", LocalDateTime.class, meetingId))
                    .isNull();
        } finally {
            jdbc.execute("drop trigger reject_recording_test_insert");
        }
    }

    @Test
    void simultaneousStartsCreateOneSessionAndChargeOnce() throws Exception {
        List<String> outcomes = race(
                () -> recordings.start(userId, meetingId),
                () -> recordings.start(userId, meetingId));
        assertThat(outcomes).containsExactlyInAnyOrder("OK", "RECORDING_ALREADY_ACTIVE");
        assertThat(balance()).isZero();
        assertThat(count("recording_sessions")).isEqualTo(1);
        assertThat(count("credit_ledgers")).isEqualTo(1);
    }

    @Test
    void twoMeetingsCannotSpendSameTeamBalance() throws Exception {
        long secondMeeting = anotherMeeting();
        List<String> outcomes = race(
                () -> recordings.start(userId, meetingId),
                () -> recordings.start(userId, secondMeeting));
        assertThat(outcomes).containsExactlyInAnyOrder("OK", "INSUFFICIENT_CREDIT");
        assertThat(balance()).isZero();
        assertThat(count("recording_sessions")).isEqualTo(1);
        assertThat(count("credit_ledgers")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from meetings where status = 'IN_PROGRESS'", Long.class))
                .isEqualTo(1);
    }

    @Test
    void creditLockSerializesRecordingWithAnotherDebit() throws Exception {
        jdbc.update("update team_credits set balance = 21 where team_id = ?", teamId);
        List<String> outcomes = race(() -> recordings.start(userId, meetingId), () -> {
            transaction.executeWithoutResult(status -> {
                TeamCredit credit = credits.findByTeamIdForUpdate(teamId).orElseThrow();
                credit.use(1L);
            });
            return null;
        });
        assertThat(outcomes).containsOnly("OK");
        assertThat(balance()).isZero();
        assertThat(count("credit_ledgers")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select balance_after from credit_ledgers", Long.class)).isIn(0L, 1L);
    }

    @ParameterizedTest
    @EnumSource(value = RecordingSessionStatus.class, names = {"RECORDING", "PAUSED"})
    void activeQueryReturnsOnlyActiveNonDeletedSessions(RecordingSessionStatus status) {
        RecordingSessionResponse session = recordings.start(userId, meetingId);
        if (status == RecordingSessionStatus.PAUSED) {
            recordings.updateStatus(userId, session.recordingSessionId(), status);
        }
        assertThat(recordings.getActive(userId, meetingId).status()).isEqualTo(status);
        recordings.updateStatus(userId, session.recordingSessionId(), RecordingSessionStatus.COMPLETED);
        assertThatThrownBy(() -> recordings.getActive(userId, meetingId))
                .isInstanceOf(BusinessException.class).extracting("errorCode")
                .isEqualTo(RecordingErrorCode.RECORDING_SESSION_NOT_FOUND);
    }

    @ParameterizedTest
    @EnumSource(value = RecordingSessionStatus.class, names = {"PAUSED", "RECORDING", "COMPLETED"})
    void duplicatePatchHasExactlyOneSuccess(RecordingSessionStatus target) throws Exception {
        long sessionId = recordings.start(userId, meetingId).recordingSessionId();
        if (target == RecordingSessionStatus.RECORDING) {
            recordings.updateStatus(userId, sessionId, RecordingSessionStatus.PAUSED);
        }
        List<String> outcomes = race(() -> recordings.updateStatus(userId, sessionId, target),
                () -> recordings.updateStatus(userId, sessionId, target));
        assertThat(outcomes).containsExactlyInAnyOrder("OK", "INVALID_RECORDING_STATUS_TRANSITION");
        assertThat(jdbc.queryForObject("select status from recording_sessions where id = ?", String.class, sessionId))
                .isEqualTo(target.name());
        assertThat(meetingStatus()).isEqualTo(target == RecordingSessionStatus.COMPLETED ? "COMPLETED" : "IN_PROGRESS");
    }

    @Test
    void racingPauseResumeCompleteCannotReopenCompletedMeeting() throws Exception {
        long sessionId = recordings.start(userId, meetingId).recordingSessionId();
        List<String> outcomes = race(
                () -> recordings.updateStatus(userId, sessionId, RecordingSessionStatus.PAUSED),
                () -> recordings.updateStatus(userId, sessionId, RecordingSessionStatus.RECORDING),
                () -> recordings.updateStatus(userId, sessionId, RecordingSessionStatus.COMPLETED));
        assertThat(outcomes).allMatch(value -> value.equals("OK") || value.equals("INVALID_RECORDING_STATUS_TRANSITION"));
        assertThat(meetingStatus()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select status from recording_sessions where id = ?", String.class, sessionId))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select ended_at from meetings where id = ?", LocalDateTime.class, meetingId))
                .isEqualTo(jdbc.queryForObject("select ended_at from recording_sessions where id = ?", LocalDateTime.class, sessionId));
    }

    @Test
    void meetingEditAndRecordingStartSerialize() throws Exception {
        List<String> outcomes = race(() -> recordings.start(userId, meetingId),
                () -> meetings.updateMeeting(userId, meetingId, new MeetingUpdateRequest("변경 제목", null, null, null, null)));
        assertThat(outcomes.get(0)).isEqualTo("OK");
        assertThat(outcomes.get(1)).isIn("OK", "MEETING_FIELD_UPDATE_NOT_ALLOWED");
        assertThat(meetingStatus()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void meetingDeleteAndRecordingStartSerialize() throws Exception {
        List<String> outcomes = race(() -> recordings.start(userId, meetingId), () -> {
            meetings.deleteMeeting(userId, meetingId);
            return null;
        });
        if (outcomes.get(0).equals("OK")) {
            assertThat(outcomes.get(1)).isEqualTo("MEETING_IN_PROGRESS");
            assertThat(balance()).isZero();
        } else {
            assertThat(outcomes).containsExactly("MEETING_NOT_FOUND", "OK");
            assertThat(balance()).isEqualTo(20);
            assertThat(count("recording_sessions")).isZero();
        }
    }

    @Test
    void participantMustBeJoinedAndNotSoftDeleted() {
        jdbc.update("update meeting_participants set deleted_at = ? where meeting_id = ?", NOW, meetingId);
        assertThatThrownBy(() -> recordings.start(userId, meetingId))
                .isInstanceOf(BusinessException.class).extracting("errorCode.code").isEqualTo("MEETING_PARTICIPANT_REQUIRED");
        jdbc.update("update meeting_participants set deleted_at = null, participation_status = 'LEFT' where meeting_id = ?", meetingId);
        assertThatThrownBy(() -> recordings.start(userId, meetingId))
                .isInstanceOf(BusinessException.class).extracting("errorCode.code").isEqualTo("MEETING_PARTICIPANT_REQUIRED");
    }

    private long anotherMeeting() {
        return Objects.requireNonNull(transaction.execute(status -> {
            Team team = entityManager.find(Team.class, teamId);
            TeamMember member = entityManager.find(TeamMember.class, memberId);
            Meeting meeting = Meeting.create(team, member, "다른 회의", "경쟁", null, NOW, 30);
            entityManager.persist(meeting);
            entityManager.persist(MeetingParticipant.create(meeting, member));
            return meeting.getId();
        }));
    }

    @SafeVarargs
    private final List<String> race(Callable<?>... actions) throws Exception {
        CountDownLatch ready = new CountDownLatch(actions.length);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(actions.length)) {
            List<Future<String>> futures = new ArrayList<>();
            for (Callable<?> action : actions) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent test did not start");
                    }
                    try {
                        action.call();
                        return "OK";
                    } catch (BusinessException e) {
                        return e.getErrorCode().getCode();
                    }
                }));
            }
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            List<String> outcomes = new ArrayList<>();
            for (Future<String> future : futures) {
                outcomes.add(future.get(20, TimeUnit.SECONDS));
            }
            return outcomes;
        }
    }

    private long balance() {
        return jdbc.queryForObject("select balance from team_credits where team_id = ?", Long.class, teamId);
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    private String meetingStatus() {
        return jdbc.queryForObject("select status from meetings where id = ?", String.class, meetingId);
    }
}
