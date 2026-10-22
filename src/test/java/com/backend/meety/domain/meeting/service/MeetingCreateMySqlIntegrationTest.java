package com.backend.meety.domain.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.backend.meety.domain.meeting.TestMeetingPolicy;
import com.backend.meety.domain.meeting.dto.MeetingCreateRequest;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingStatus;
import com.backend.meety.domain.meeting.exception.MeetingErrorCode;
import com.backend.meety.domain.meeting.exception.MeetingException;
import com.backend.meety.domain.meeting.repository.MeetingParticipantRepository;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.domain.team.repository.TeamRepository;
import com.backend.meety.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
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

/** Only an explicitly supplied disposable local meeting_test_303 database may be initialized. */
@SpringJUnitConfig(MeetingCreateMySqlIntegrationTest.DatabaseConfig.class)
@EnabledIfEnvironmentVariable(named = "MEETING_TEST_DB_URL", matches = ".+")
class MeetingCreateMySqlIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);
    private static final LocalDateTime CREATED_AT = TODAY.atTime(9, 0);
    private static final LocalDateTime SCHEDULED_AT = TODAY.atTime(15, 0);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneOffset.UTC);

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaAuditing(dateTimeProviderRef = "testDateTimeProvider")
    @EnableJpaRepositories(basePackageClasses = {MeetingRepository.class, TeamRepository.class})
    static class DatabaseConfig {
        @Bean
        DataSource dataSource() {
            String url = System.getenv("MEETING_TEST_DB_URL");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/meeting_test_303(?:\\?.*)?")) {
                throw new IllegalArgumentException("A disposable local meeting_test_303 database is required");
            }
            return new DriverManagerDataSource(url, System.getenv().getOrDefault("MEETING_TEST_DB_USER", "root"),
                    System.getenv().getOrDefault("MEETING_TEST_DB_PASSWORD", ""));
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
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
        DateTimeProvider testDateTimeProvider() {
            return () -> Optional.of(CREATED_AT);
        }

        @Bean
        MeetingService meetingService(MeetingRepository meetings, TeamRepository teams, TeamMemberRepository members,
                MeetingParticipantRepository participants, ApplicationEventPublisher events) {
            return new MeetingService(meetings, teams, members, participants, CLOCK, events, TestMeetingPolicy.DEFAULT);
        }
    }

    @Autowired private MeetingService service;
    @Autowired private MeetingRepository meetings;
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
        for (String table : List.of("meeting_participants", "meetings", "team_members", "teams", "users")) {
            jdbc.update("delete from " + table);
        }
        transaction.executeWithoutResult(status -> {
            User user = User.create();
            Team team = Team.create("예정 회의 테스트");
            entityManager.persist(user);
            entityManager.persist(team);
            entityManager.persist(TeamMember.createLeader(user, team, "팀장"));
            userId = user.getId();
            teamId = team.getId();
        });
    }

    @Test
    @DisplayName("실제 MySQL은 입력한 KST 예정 시각과 생성 시각을 별도로 저장하고 예정 범위로 조회한다")
    void persistsLocalTimeWithoutReplacingCreatedAt() {
        // 테스트 목적:
        // 입력 예정 시각이 UTC로 이동하거나 서버 시각으로 덮이지 않고 DB와 범위 조회에 유지되는지 검증한다.
        // given
        var request = request(SCHEDULED_AT);
        // when
        var response = service.createMeeting(userId, teamId, request);
        var stored = jdbc.queryForMap("select scheduled_at, created_at, status from meetings where id = ?", response.meetingId());
        var inWindow = transaction.execute(status -> entityManager.createQuery("""
                select m.id from Meeting m where m.scheduledAt >= :from and m.scheduledAt < :to
                """, Long.class).setParameter("from", SCHEDULED_AT)
                .setParameter("to", SCHEDULED_AT.plusMinutes(1)).getResultList());
        // then
        assertThat(jdbc.queryForObject("select scheduled_at from meetings where id = ?",
                LocalDateTime.class, response.meetingId())).isEqualTo(SCHEDULED_AT);
        assertThat(response.scheduledAt()).isEqualTo(SCHEDULED_AT);
        assertThat(response.createdAt()).isEqualTo(CREATED_AT).isNotEqualTo(response.scheduledAt());
        assertThat(stored.get("status")).isEqualTo("WAITING");
        assertThat(inWindow).containsExactly(response.meetingId());
    }

    @Test
    @DisplayName("예정 날짜가 모두 달라도 오늘 생성한 20개에는 삭제·완료 회의를 포함해 21번째를 거절한다")
    void differentScheduledDaysCannotBypassDailyLimit() {
        // 테스트 목적:
        // 생성 제한은 예정 날짜나 삭제·진행 상태가 아니라 실제 당일 생성 이력 20개를 기준으로 하는지 검증한다.
        // given
        var ids = new ArrayList<Long>();
        for (int i = 0; i < 20; i++) {
            ids.add(service.createMeeting(userId, teamId, request(SCHEDULED_AT.plusDays(i))).meetingId());
        }
        transaction.executeWithoutResult(status -> {
            Meeting deleted = entityManager.find(Meeting.class, ids.get(0));
            deleted.softDelete(CREATED_AT.plusMinutes(1));
            entityManager.find(Meeting.class, ids.get(1)).complete(CREATED_AT.plusMinutes(2));
            entityManager.find(Meeting.class, ids.get(2)).start(CREATED_AT.plusMinutes(3));
        });
        // when
        // then
        assertThatThrownBy(() -> service.createMeeting(userId, teamId, request(SCHEDULED_AT.plusYears(1))))
                .isInstanceOf(MeetingException.class).extracting("errorCode")
                .isEqualTo(MeetingErrorCode.DAILY_MEETING_LIMIT_EXCEEDED);
        assertThat(meetings.countCreatedTodayByTeamId(teamId, TODAY.atStartOfDay(), TODAY.plusDays(1).atStartOfDay()))
                .isEqualTo(20);
        assertThat(jdbc.queryForObject("select count(*) from meetings", Long.class)).isEqualTo(20);
    }

    @Test
    @DisplayName("생성 횟수는 createdAt의 당일 자정을 포함하고 다음 자정은 제외한다")
    void countUsesHalfOpenCreatedAtRange() {
        // 테스트 목적:
        // 예정 시각이 같은 날이어도 다른 날 생성한 이력은 제외하고 당일 생성 경계만 포함하는지 검증한다.
        // given
        long yesterday = service.createMeeting(userId, teamId, request(SCHEDULED_AT)).meetingId();
        long start = service.createMeeting(userId, teamId, request(SCHEDULED_AT.plusDays(10))).meetingId();
        long nextDay = service.createMeeting(userId, teamId, request(SCHEDULED_AT)).meetingId();
        jdbc.update("update meetings set created_at = ? where id = ?", TODAY.atStartOfDay().minusSeconds(1), yesterday);
        jdbc.update("update meetings set created_at = ? where id = ?", TODAY.atStartOfDay(), start);
        jdbc.update("update meetings set created_at = ? where id = ?", TODAY.plusDays(1).atStartOfDay(), nextDay);
        // when
        long count = meetings.countCreatedTodayByTeamId(teamId, TODAY.atStartOfDay(), TODAY.plusDays(1).atStartOfDay());
        // then
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("prewarm 범위 조회는 실제 MySQL에서 미삭제 WAITING 회의만 하한 포함·상한 제외로 정렬한다")
    void findsOnlyUpcomingWaitingMeetingsForPrewarm() {
        // 테스트 목적:
        // #303에서 저장한 예정 시각으로 범위를 조회하고 시작·완료·삭제 회의와 양쪽 경계를 정확히 처리하는지 검증한다.
        // given
        LocalDateTime from = TODAY.atTime(14, 59);
        long before = service.createMeeting(userId, teamId, request(from.minusSeconds(1))).meetingId();
        long lower = service.createMeeting(userId, teamId, request(from)).meetingId();
        long later = service.createMeeting(userId, teamId, request(from.plusSeconds(30))).meetingId();
        long laterTie = service.createMeeting(userId, teamId, request(from.plusSeconds(30))).meetingId();
        long upper = service.createMeeting(userId, teamId, request(from.plusMinutes(1))).meetingId();
        long inProgress = service.createMeeting(userId, teamId, request(from.plusSeconds(10))).meetingId();
        long completed = service.createMeeting(userId, teamId, request(from.plusSeconds(20))).meetingId();
        long deleted = service.createMeeting(userId, teamId, request(from.plusSeconds(5))).meetingId();
        transaction.executeWithoutResult(status -> {
            entityManager.find(Meeting.class, inProgress).start(CREATED_AT);
            Meeting ended = entityManager.find(Meeting.class, completed);
            ended.start(CREATED_AT);
            ended.complete(CREATED_AT.plusMinutes(1));
            entityManager.find(Meeting.class, deleted).softDelete(CREATED_AT);
        });

        // when
        var ids = meetings.findWaitingForPrewarm(from, from.plusMinutes(1)).stream().map(Meeting::getId).toList();

        // then
        assertThat(ids).containsExactly(lower, later, laterTie);
        assertThat(ids).doesNotContain(before, upper, inProgress, completed, deleted);
    }

    @RepeatedTest(3)
    @DisplayName("실제 MySQL에서 남은 한 자리에 동시 생성해도 Team 잠금으로 20개를 초과하지 않는다")
    void concurrentCreationRespectsTwentyMeetingLimit() throws Exception {
        // 테스트 목적:
        // 같은 팀의 동시 생성이 예정 날짜가 달라도 마지막 한 자리만 선점하고 나머지는 거절되는지 검증한다.
        // given
        for (int i = 0; i < 19; i++) {
            service.createMeeting(userId, teamId, request(SCHEDULED_AT.plusDays(i)));
        }
        var start = new CountDownLatch(1);
        var ready = new CountDownLatch(8);
        List<Future<Boolean>> results = new ArrayList<>();
        try (var workers = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++) {
                var scheduledAt = SCHEDULED_AT.plusDays(20 + i);
                results.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("Start latch timed out"); }
                    try {
                        service.createMeeting(userId, teamId, request(scheduledAt));
                        return true;
                    } catch (MeetingException e) {
                        assertThat(e.getErrorCode()).isEqualTo(MeetingErrorCode.DAILY_MEETING_LIMIT_EXCEEDED);
                        return false;
                    }
                }));
            }
            // when
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int succeeded = 0;
            for (var result : results) {
                if (result.get(15, TimeUnit.SECONDS)) { succeeded++; }
            }
            // then
            assertThat(succeeded).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from meetings", Long.class)).isEqualTo(20);
        } finally {
            start.countDown();
        }
    }

    @Test
    @DisplayName("대기 회의는 예정 시각순이고 진행·완료 회의는 기존 시작 시각순을 유지한다")
    void listsByScheduledOrActualStartWithoutChangingSortPolicy() {
        // 테스트 목적:
        // 생성 순서와 예정 순서가 달라도 WAITING만 예정 시각을 쓰고 다른 상태는 실제 시작 시각을 쓰는지 검증한다.
        // given
        long a = service.createMeeting(userId, teamId, request(TODAY.atTime(16, 0))).meetingId();
        long b = service.createMeeting(userId, teamId, request(TODAY.atTime(14, 0))).meetingId();
        long inProgress = service.createMeeting(userId, teamId, request(TODAY.atTime(12, 0))).meetingId();
        long completed = service.createMeeting(userId, teamId, request(TODAY.atTime(17, 0))).meetingId();
        transaction.executeWithoutResult(status -> {
            entityManager.find(Meeting.class, inProgress).start(TODAY.atTime(15, 0));
            var meeting = entityManager.find(Meeting.class, completed);
            meeting.start(TODAY.atTime(13, 0));
            meeting.complete(TODAY.atTime(13, 30));
        });
        // when
        var response = service.getMeetings(userId, teamId, null, TODAY, TODAY, null);
        // then
        assertThat(a).isLessThan(b);
        assertThat(response.groups()).hasSize(1);
        assertThat(response.groups().getFirst().meetings()).extracting(item -> item.meetingId())
                .containsExactly(completed, b, inProgress, a);
        assertThat(response.groups().getFirst().meetings()).extracting(item -> item.status())
                .containsExactly(MeetingStatus.COMPLETED, MeetingStatus.WAITING, MeetingStatus.IN_PROGRESS, MeetingStatus.WAITING);
    }

    private MeetingCreateRequest request(LocalDateTime scheduledAt) {
        return new MeetingCreateRequest("주간 회의", "업무 공유", null, 30,
                List.of(), List.of("외부 참석자"), scheduledAt);
    }
}
