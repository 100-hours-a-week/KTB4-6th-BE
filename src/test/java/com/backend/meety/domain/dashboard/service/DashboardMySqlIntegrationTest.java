package com.backend.meety.domain.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.dashboard.dto.DashboardResponse;
import com.backend.meety.domain.meeting.MetricPolicy;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingMetric;
import com.backend.meety.domain.meeting.entity.MeetingSummary;
import com.backend.meety.domain.meeting.repository.MeetingMetricRepository;
import com.backend.meety.domain.meeting.repository.MeetingSummaryRepository;
import com.backend.meety.domain.recording.entity.RecordingSession;
import com.backend.meety.domain.recording.repository.RecordingSessionRepository;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.exception.TeamErrorCode;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.domain.team.repository.TeamRepository;
import com.backend.meety.domain.user.entity.User;
import com.backend.meety.global.exception.BusinessException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Explicitly opts into a disposable local dashboard_test_299 database without loading application.yaml. */
@SpringJUnitConfig(DashboardMySqlIntegrationTest.DatabaseConfig.class)
@EnabledIfEnvironmentVariable(named = "DASHBOARD_TEST_DB_URL", matches = ".+")
@Transactional
class DashboardMySqlIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 12);

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses = {
            RecordingSessionRepository.class, MeetingSummaryRepository.class, TeamRepository.class
    })
    static class DatabaseConfig {

        @Bean
        DataSource dataSource() {
            String url = System.getenv("DASHBOARD_TEST_DB_URL");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/dashboard_test_299(?:\\?.*)?")) {
                throw new IllegalArgumentException("A disposable local dashboard_test_299 database is required");
            }
            return new DriverManagerDataSource(url,
                    System.getenv().getOrDefault("DASHBOARD_TEST_DB_USER", "root"),
                    System.getenv().getOrDefault("DASHBOARD_TEST_DB_PASSWORD", ""));
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

        @Bean
        DashboardService dashboardService(
                TeamRepository teams, TeamMemberRepository members, RecordingSessionRepository recordings,
                MeetingSummaryRepository summaries, MeetingMetricRepository metrics
        ) {
            Clock clock = Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneId.of("Asia/Seoul"));
            return new DashboardService(teams, members, recordings, summaries, metrics, new ObjectMapper(), clock);
        }
    }

    @Autowired
    private DashboardService service;
    @PersistenceContext
    private EntityManager entityManager;

    private Team team;
    private TeamMember member;
    private User user;

    @BeforeEach
    void setUp() {
        team = persist(Team.create("대시보드 테스트"));
        user = persist(User.create());
        member = persist(TeamMember.createLeader(user, team, "팀장"));
    }

    @Test
    @DisplayName("실제 MySQL에서 종료일 반열린 구간과 삭제·완료 조건을 적용하고 최신 세션만 집계한다")
    void queryFiltersDatesStatusesAndDuplicates() {
        // 테스트 목적:
        // 시작 자정은 포함하고 다음 자정은 제외하며 삭제·미완료 데이터도 제외하고
        // 다중 세션은 최신 한 건만 선택해 요약·차트·일간 목록이 일치하는지 실제 DB에서 검증한다.

        // given
        RecordingSession atStart = completedSession(DATE.minusDays(1).atTime(23, 50), DATE.atStartOfDay());
        RecordingSession beforeEnd = completedSession(DATE.atTime(23, 58, 59), DATE.atTime(23, 59, 59));
        completedSession(DATE.minusDays(1).atTime(23, 58), DATE.minusDays(1).atTime(23, 59));
        completedSession(DATE.atTime(23, 50), DATE.plusDays(1).atStartOfDay());
        RecordingSession deletedMeeting = completedSession(DATE.atTime(9, 0), DATE.atTime(9, 10));
        deletedMeeting.getMeeting().softDelete(DATE.atTime(12, 0));
        RecordingSession deletedSession = completedSession(DATE.atTime(9, 0), DATE.atTime(9, 10));
        ReflectionTestUtils.setField(deletedSession, "deletedAt", DATE.atTime(12, 0));
        Meeting ongoing = meeting(DATE.atTime(9, 0));
        RecordingSession finishedInOngoingMeeting = persist(RecordingSession.start(ongoing, member, DATE.atTime(9, 0)));
        finishedInOngoingMeeting.complete(DATE.atTime(9, 10));
        Meeting completedWithoutFinishedSession = meeting(DATE.atTime(9, 0));
        persist(RecordingSession.start(completedWithoutFinishedSession, member, DATE.atTime(9, 0)));
        completedWithoutFinishedSession.complete(DATE.atTime(9, 10));
        RecordingSession earlier = completedSession(DATE.atTime(10, 0), DATE.atTime(10, 10));
        RecordingSession latest = persist(RecordingSession.start(earlier.getMeeting(), member, DATE.atTime(11, 0)));
        latest.complete(DATE.atTime(11, 2));
        earlier.getMeeting().complete(DATE.atTime(11, 2));
        ReflectionTestUtils.setField(latest, "totalPausedDurationMs", 60_000L);
        entityManager.flush();
        entityManager.clear();

        // when
        DashboardResponse daily = service.getDashboard(user.getId(), team.getId(), DATE, DATE);
        DashboardResponse weekly = service.getDashboard(user.getId(), team.getId(), DATE, DATE.plusDays(6));

        // then
        assertThat(daily.meetings()).extracting("meetingId")
                .containsExactly(atStart.getMeeting().getId(), earlier.getMeeting().getId(), beforeEnd.getMeeting().getId());
        assertThat(daily.meetings()).extracting("actualDurationSeconds").containsExactly(600L, 120L, 60L);
        assertThat(daily.summary().totalMeetings()).isEqualTo(3L);
        assertThat(daily.summary().totalMeetingDurationSeconds()).isEqualTo(780L);
        assertThat(daily.chart()).hasSize(1);
        assertThat(daily.chart().getFirst().meetingCount()).isEqualTo(3L);
        assertThat(daily.chart().getFirst().actualDurationSeconds()).isEqualTo(780L);
        assertThat(weekly.summary().totalMeetings()).isEqualTo(4L);
        assertThat(weekly.summary().totalMeetingDurationSeconds()).isEqualTo(1380L);
        assertThat(weekly.chart().getFirst()).isEqualTo(daily.chart().getFirst());
        assertThat(weekly.chart().get(1).meetingCount()).isEqualTo(1L);
        assertThat(weekly.chart().get(1).actualDurationSeconds()).isEqualTo(600L);
        assertThat(weekly.chart().stream().mapToLong(point -> point.meetingCount()).sum())
                .isEqualTo(weekly.summary().totalMeetings());
        assertThat(weekly.chart().stream().mapToLong(point -> point.actualDurationSeconds()).sum())
                .isEqualTo(weekly.summary().totalMeetingDurationSeconds());
        assertThat(weekly.meetings()).isEmpty();
    }

    @Test
    @DisplayName("실제 MySQL에서 미완료 최신 요약과 늦은 이전 Metric을 제외하고 최신 완료 요약의 Metric만 선택한다")
    void selectsMetricForLatestCompletedSummaryVersion() {
        // 테스트 목적:
        // 완료 요약보다 높은 버전의 미완료 요약과 더 늦게 저장된 이전 Metric이 존재해도
        // 최신 완료 요약의 Metric만 선택해 ms를 초로 변환하는지 실제 DB에서 검증한다.

        // given
        Meeting meeting = completedSession(DATE.atTime(10, 0), DATE.atTime(10, 2)).getMeeting();
        MeetingSummary old = summary(meeting, 1, true);
        MeetingSummary current = summary(meeting, 2, true);
        MeetingSummary pending = summary(meeting, 3, false);
        metric(current, "현재 안건", 1501L);
        metric(old, "늦은 이전 안건", 9000L);
        metric(pending, "미완료 요약 안건", 9000L);
        entityManager.flush();
        entityManager.clear();

        // when
        DashboardResponse response = service.getDashboard(user.getId(), team.getId(), DATE, DATE);

        // then
        assertThat(response.topics()).extracting("name").containsExactly("현재 안건");
        assertThat(response.topics().getFirst().durationSeconds()).isEqualByComparingTo("1.501");
        assertThat(response.summary().totalContextOutSeconds()).isEqualByComparingTo("1.501");
        assertThat(response.summary().contextOutRate()).isEqualByComparingTo("1.25");
        assertThat(response.summary().contextOutMeetingDurationSeconds()).isEqualTo(120L);
    }

    @Test
    @DisplayName("실제 MySQL에서 최신 완료 요약에 Metric이 없으면 이전 Metric으로 대체하지 않는다")
    void doesNotFallbackToOlderSummaryMetric() {
        // 테스트 목적:
        // 요약 재생성 후 새 Metric이 아직 없을 때
        // 이전 요약의 지표를 최신 결과처럼 반환하지 않는지 실제 DB에서 검증한다.

        // given
        Meeting meeting = completedSession(DATE.atTime(10, 0), DATE.atTime(10, 2)).getMeeting();
        MeetingSummary old = summary(meeting, 1, true);
        summary(meeting, 2, true);
        metric(old, "이전 안건", 9000L);
        entityManager.flush();
        entityManager.clear();

        // when
        DashboardResponse response = service.getDashboard(user.getId(), team.getId(), DATE, DATE);

        // then
        assertThat(response.summary().totalMeetings()).isEqualTo(1L);
        assertThat(response.summary().totalContextOutSeconds()).isZero();
        assertThat(response.topics()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = MembershipStatus.class, names = {"ACTIVE", "LEFT", "KICKED"})
    @DisplayName("실제 MySQL에서 ACTIVE 팀원만 조회하고 탈퇴·강퇴 팀원은 차단한다")
    void requiresActiveMembership(MembershipStatus membershipStatus) {
        // 테스트 목적:
        // 실제 멤버십 상태가 ACTIVE일 때만 대시보드에 접근할 수 있고
        // 탈퇴 및 강퇴 상태는 기존 팀 권한 검사로 거부되는지 검증한다.

        // given
        if (membershipStatus == MembershipStatus.LEFT) {
            member.leave(DATE.atStartOfDay());
        } else if (membershipStatus == MembershipStatus.KICKED) {
            member.kick(DATE.atStartOfDay());
        }
        entityManager.flush();
        entityManager.clear();

        // when, then
        if (membershipStatus == MembershipStatus.ACTIVE) {
            assertThat(service.getDashboard(user.getId(), team.getId(), DATE, DATE).periodDays()).isEqualTo(1);
        } else {
            assertThatThrownBy(() -> service.getDashboard(user.getId(), team.getId(), DATE, DATE))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_ACCESS_DENIED));
        }
    }

    @Test
    @DisplayName("실제 MySQL에서 다른 팀의 ACTIVE 사용자도 해당 팀 대시보드에 접근할 수 없다")
    void rejectsActiveMemberOfAnotherTeam() {
        // 테스트 목적:
        // 다른 팀에 ACTIVE 멤버십을 가진 사용자라도
        // 요청한 팀의 멤버가 아니면 대시보드가 차단되는지 검증한다.

        // given
        Team otherTeam = persist(Team.create("다른 팀"));
        User otherUser = persist(User.create());
        persist(TeamMember.createLeader(otherUser, otherTeam, "다른 팀장"));
        entityManager.flush();
        entityManager.clear();

        // when, then
        assertThatThrownBy(() -> service.getDashboard(otherUser.getId(), team.getId(), DATE, DATE))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_ACCESS_DENIED));
    }

    private RecordingSession completedSession(LocalDateTime start, LocalDateTime end) {
        Meeting meeting = meeting(start);
        RecordingSession session = persist(RecordingSession.start(meeting, member, start));
        session.complete(end);
        meeting.complete(end);
        return session;
    }

    private Meeting meeting(LocalDateTime start) {
        Meeting meeting = persist(Meeting.create(team, member, "대시보드 회의", "집계 검증", null, start, 10));
        meeting.start(start);
        return meeting;
    }

    private MeetingSummary summary(Meeting meeting, long version, boolean completed) {
        AiRequest request = persist(AiRequest.create(team, member,
                "SUMMARY:" + meeting.getId() + ":" + version, AiRequestType.SUMMARY));
        MeetingSummary summary = persist(MeetingSummary.createPending(request, team, meeting, version, null));
        if (completed) {
            request.markCompleted();
            summary.complete("완료 요약");
        }
        return summary;
    }

    private void metric(MeetingSummary summary, String title, long durationMs) {
        AiRequest request = persist(AiRequest.create(team, member,
                MetricPolicy.idempotencyKey(summary.getId()), AiRequestType.METRIC));
        request.markCompleted();
        persist(MeetingMetric.create(request, summary.getMeeting(),
                "{\"agendas\":[{\"title\":\"" + title + "\",\"durationMs\":" + durationMs + "}]}", durationMs));
    }

    private <T> T persist(T entity) {
        entityManager.persist(entity);
        return entity;
    }
}
