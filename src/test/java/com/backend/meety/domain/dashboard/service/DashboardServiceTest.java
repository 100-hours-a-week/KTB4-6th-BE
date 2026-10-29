package com.backend.meety.domain.dashboard.service;

import static com.backend.meety.domain.recording.RecordingFixtures.member;
import static com.backend.meety.domain.recording.RecordingFixtures.team;
import static com.backend.meety.domain.recording.RecordingFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.dashboard.dto.DashboardResponse;
import com.backend.meety.domain.dashboard.dto.DashboardChartPointResponse;
import com.backend.meety.domain.dashboard.exception.DashboardErrorCode;
import com.backend.meety.domain.meeting.MetricPolicy;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingMetric;
import com.backend.meety.domain.meeting.repository.MeetingMetricRepository;
import com.backend.meety.domain.meeting.repository.MeetingSummaryRepository;
import com.backend.meety.domain.meeting.repository.MeetingSummaryRepository.LatestCompletedSummaryProjection;
import com.backend.meety.domain.recording.entity.RecordingSession;
import com.backend.meety.domain.recording.repository.RecordingSessionRepository;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.exception.TeamErrorCode;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.domain.team.repository.TeamRepository;
import com.backend.meety.global.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class DashboardServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDate FROM = LocalDate.of(2026, 10, 12);
    private static final LocalDate TO = LocalDate.of(2026, 10, 18);

    private final TeamRepository teams = mock(TeamRepository.class);
    private final TeamMemberRepository members = mock(TeamMemberRepository.class);
    private final RecordingSessionRepository recordings = mock(RecordingSessionRepository.class);
    private final MeetingSummaryRepository summaries = mock(MeetingSummaryRepository.class);
    private final MeetingMetricRepository metrics = mock(MeetingMetricRepository.class);
    private final DashboardService service = new DashboardService(
            teams, members, recordings, summaries, metrics, new ObjectMapper(), CLOCK);

    private Team team;
    private TeamMember member;

    @BeforeEach
    void setUp() {
        team = team();
        member = member(team);
        when(teams.findByIdAndDeletedAtIsNull(2L)).thenReturn(Optional.of(team));
        when(members.existsByTeamIdAndUserIdAndMembershipStatus(2L, 1L, MembershipStatus.ACTIVE))
                .thenReturn(true);
        when(recordings.findDashboardCompletedSessions(any(), any(), any())).thenReturn(List.of());
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-12, 2026-10-12, 1",
            "2026-10-12, 2026-10-18, 7",
            "2026-09-28, 2026-10-04, 7",
            "2025-12-29, 2026-01-04, 7"
    })
    @DisplayName("일간 또는 월요일부터 일요일까지의 주간은 날짜별 차트를 반환한다")
    void supportedPeriodsReturnDailyChartPoints(LocalDate from, LocalDate to, int days) {
        // 테스트 목적:
        // 일간과 월·연도 경계를 포함한 정상 주간 요청이
        // 요청한 날짜 범위와 정확히 1개 또는 7개의 날짜별 차트를 반환하는지 검증한다.

        // given

        // when
        DashboardResponse response = service.getDashboard(1L, 2L, from, to);

        // then
        assertThat(response.periodDays()).isEqualTo(days);
        assertThat(response.startDate()).isEqualTo(from);
        assertThat(response.endDate()).isEqualTo(to);
        assertThat(response.chart()).hasSize(days);
        for (int i = 0; i < days; i++) {
            assertThat(response.chart().get(i).startDate()).isEqualTo(from.plusDays(i));
            assertThat(response.chart().get(i).endDate()).isEqualTo(from.plusDays(i));
        }
        verify(recordings).findDashboardCompletedSessions(2L, from.atStartOfDay(), to.plusDays(1).atStartOfDay());
    }

    @Test
    @DisplayName("회의가 없는 기간은 0 요약과 빈 구간 7개를 반환한다")
    void emptyPeriodReturnsZeroSummaryAndSevenEmptyBuckets() {
        // 테스트 목적:
        // 조회 기간에 완료 회의와 Metric 데이터가 전혀 없어도
        // 오류 없이 0으로 채운 요약과 7개 차트 구간을 반환하는지 검증한다.

        // given

        // when
        DashboardResponse response = service.getDashboard(1L, 2L, FROM, TO);

        // then
        assertThat(response.summary().totalMeetings()).isZero();
        assertThat(response.summary().totalMeetingDurationSeconds()).isZero();
        assertThat(response.summary().averageMeetingDurationSeconds()).isZero();
        assertThat(response.summary().overtimeMeetingRate()).isEqualByComparingTo("0.00");
        assertThat(response.summary().contextOutRate()).isEqualByComparingTo("0.00");
        assertThat(response.chart()).hasSize(7);
        assertThat(response.chart())
                .allSatisfy(point -> {
                    assertThat(point.meetingCount()).isZero();
                    assertThat(point.targetDurationSeconds()).isZero();
                    assertThat(point.actualDurationSeconds()).isZero();
                    assertThat(point.overtimeMeetings()).isZero();
                });
        assertThat(response.meetings()).isEmpty();
        assertThat(response.topics()).isEmpty();
    }

    @Test
    @DisplayName("예정시간과 실제 녹음 경과시간을 합산하고 일시정지 시간도 포함한다")
    void aggregatesTargetAndActualDurationsIncludingPauseElapsedTime() {
        // 테스트 목적:
        // 대시보드 시간 집계가 별도 duration 컬럼 없이 완료 녹음의 시작·종료 시각 차이를 사용하고
        // 일시정지 구간도 회의 경과시간에 포함하는지 검증한다.

        // given
        RecordingSession first = session(101L, 701L,
                LocalDateTime.of(2026, 10, 12, 10, 0),
                LocalDateTime.of(2026, 10, 12, 10, 2),
                1);
        RecordingSession second = session(102L, 702L,
                LocalDateTime.of(2026, 10, 12, 11, 0),
                LocalDateTime.of(2026, 10, 12, 11, 5),
                4);
        ReflectionTestUtils.setField(first, "totalPausedDurationMs", 60_000L);
        when(recordings.findDashboardCompletedSessions(any(), any(), any()))
                .thenReturn(List.of(first, second));

        // when
        DashboardResponse response = service.getDashboard(1L, 2L, FROM, TO);

        // then
        assertThat(response.summary().totalMeetings()).isEqualTo(2L);
        assertThat(response.summary().totalMeetingDurationSeconds()).isEqualTo(420L);
        assertThat(response.summary().averageMeetingDurationSeconds()).isEqualTo(210L);
        assertThat(response.chart().getFirst().targetDurationSeconds()).isEqualTo(300L);
        assertThat(response.chart().getFirst().actualDurationSeconds()).isEqualTo(420L);
        assertThat(response.chart().getFirst().meetingCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("초과 회의 수와 비율은 회의별 초과 여부를 기준으로 집계한다")
    void aggregatesOvertimeMeetingsIndividually() {
        // 테스트 목적:
        // 예정시간이 다른 회의들이 섞여 있을 때
        // 회의별 초과 여부로 초과 회의 수와 전체 회의 대비 비율을 계산하는지 검증한다.

        // given
        RecordingSession shortOvertime = session(101L, 701L,
                LocalDateTime.of(2026, 10, 12, 10, 0),
                LocalDateTime.of(2026, 10, 12, 10, 1, 30),
                1);
        RecordingSession longOvertime = session(102L, 702L,
                LocalDateTime.of(2026, 10, 12, 11, 0),
                LocalDateTime.of(2026, 10, 12, 11, 4),
                2);
        RecordingSession normal = session(103L, 703L,
                LocalDateTime.of(2026, 10, 12, 12, 0),
                LocalDateTime.of(2026, 10, 12, 12, 1),
                2);
        when(recordings.findDashboardCompletedSessions(any(), any(), any()))
                .thenReturn(List.of(shortOvertime, longOvertime, normal));

        // when
        DashboardResponse response = service.getDashboard(1L, 2L, FROM, TO);

        // then
        assertThat(response.summary().overtimeMeetings()).isEqualTo(2L);
        assertThat(response.summary().overtimeMeetingRate()).isEqualByComparingTo("66.67");
        assertThat(response.chart().getFirst().overtimeMeetings()).isEqualTo(2L);
    }

    @Test
    @DisplayName("회의당 녹음 세션이 중복 조회되어도 회의 수를 중복 집계하지 않는다")
    void deduplicatesMultipleRecordingSessionsForSameMeeting() {
        // 테스트 목적:
        // 같은 회의에 대해 완료 녹음 세션 행이 여러 개 조회되더라도
        // 회의 수와 시간 합계가 중복으로 부풀지 않는지 검증한다.

        // given
        RecordingSession latest = session(101L, 702L,
                LocalDateTime.of(2026, 10, 12, 10, 0),
                LocalDateTime.of(2026, 10, 12, 10, 2),
                1);
        RecordingSession duplicate = session(101L, 701L,
                LocalDateTime.of(2026, 10, 12, 9, 0),
                LocalDateTime.of(2026, 10, 12, 9, 10),
                1);
        when(recordings.findDashboardCompletedSessions(any(), any(), any()))
                .thenReturn(List.of(latest, duplicate));

        // when
        DashboardResponse response = service.getDashboard(1L, 2L, FROM, FROM);

        // then
        assertThat(response.summary().totalMeetings()).isEqualTo(1L);
        assertThat(response.summary().totalMeetingDurationSeconds()).isEqualTo(120L);
        assertThat(response.meetings()).hasSize(1);
        assertThat(response.meetings().getFirst().actualDurationSeconds()).isEqualTo(120L);
        assertThat(response.chart().getFirst().meetingCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("최초 Summary 기준 Metric을 맥락 이탈과 안건 집계에 사용한다")
    void selectsFirstSummaryMetricWhenItIsLatestCompletedSummary() {
        // 테스트 목적:
        // 최초 요약만 완료된 회의는 METRIC:SUMMARY:{summaryId}로 연결된 Metric을
        // 대시보드의 최신 지표로 선택하는지 검증한다.

        // given
        RecordingSession session = session(101L, 701L,
                LocalDateTime.of(2026, 10, 12, 10, 0),
                LocalDateTime.of(2026, 10, 12, 10, 2),
                2);
        when(recordings.findDashboardCompletedSessions(any(), any(), any())).thenReturn(List.of(session));
        when(summaries.findLatestCompletedSummaryIdsByMeetingIds(List.of(101L)))
                .thenReturn(List.of(new LatestSummaryRow(101L, 502L)));
        when(metrics.findDashboardCompletedMetrics(any(), any(), any()))
                .thenReturn(List.of(metric(901L, 801L, session.getMeeting(), 502L,
                        "{\"agendas\":[{\"title\":\"WebSocket ACK 방식\",\"durationMs\":1500}]}",
                        1500L)));

        // when
        DashboardResponse response = service.getDashboard(1L, 2L, FROM, TO);

        // then
        assertThat(response.summary().totalContextOutSeconds()).isEqualByComparingTo("1.5");
        assertThat(response.summary().contextOutRate()).isEqualByComparingTo("1.25");
        assertThat(response.summary().contextOutMeetingDurationSeconds()).isEqualTo(120L);
        assertThat(response.topics()).hasSize(1);
        assertThat(response.topics().getFirst().name()).isEqualTo("WebSocket ACK 방식");
        assertThat(response.topics().getFirst().durationSeconds()).isEqualByComparingTo("1.5");
    }

    @Test
    @DisplayName("요약 재생성 후에는 최신 Summary에 연결된 Metric만 선택한다")
    void selectsRegeneratedSummaryMetricAndIgnoresOlderMetric() {
        // 테스트 목적:
        // 요약 재생성으로 최신 완료 Summary 버전이 바뀐 경우
        // 이전 Summary의 Metric이 있어도 최신 Summary에 연결된 Metric만 집계하는지 검증한다.

        // given
        RecordingSession session = session(101L, 701L,
                LocalDateTime.of(2026, 10, 12, 10, 0),
                LocalDateTime.of(2026, 10, 12, 10, 2),
                2);
        when(recordings.findDashboardCompletedSessions(any(), any(), any())).thenReturn(List.of(session));
        when(summaries.findLatestCompletedSummaryIdsByMeetingIds(List.of(101L)))
                .thenReturn(List.of(new LatestSummaryRow(101L, 503L)));
        MeetingMetric oldMetric = metric(901L, 801L, session.getMeeting(), 502L,
                "{\"agendas\":[{\"title\":\"이전 안건\",\"durationMs\":9000}]}",
                9000L);
        MeetingMetric latestMetric = metric(902L, 802L, session.getMeeting(), 503L,
                "{\"agendas\":[{\"title\":\"최신 안건\",\"durationMs\":1500}]}",
                1500L);
        when(metrics.findDashboardCompletedMetrics(any(), any(), any()))
                .thenReturn(List.of(oldMetric, latestMetric));

        // when
        DashboardResponse response = service.getDashboard(1L, 2L, FROM, TO);

        // then
        assertThat(response.summary().totalContextOutSeconds()).isEqualByComparingTo("1.5");
        assertThat(response.topics()).extracting("name").containsExactly("최신 안건");
    }

    @Test
    @DisplayName("오래된 Summary의 Metric이 늦게 완료되어도 최신 지표로 선택하지 않는다")
    void ignoresLateOldMetricWhenLatestSummaryHasNoMetric() {
        // 테스트 목적:
        // 최신 완료 Summary 버전의 Metric은 아직 없고 이전 Summary의 Metric만 늦게 완료된 경우
        // 오래된 지표를 최신 대시보드 지표로 오인하지 않는지 검증한다.

        // given
        RecordingSession session = session(101L, 701L,
                LocalDateTime.of(2026, 10, 12, 10, 0),
                LocalDateTime.of(2026, 10, 12, 10, 2),
                2);
        when(recordings.findDashboardCompletedSessions(any(), any(), any())).thenReturn(List.of(session));
        when(summaries.findLatestCompletedSummaryIdsByMeetingIds(List.of(101L)))
                .thenReturn(List.of(new LatestSummaryRow(101L, 503L)));
        when(metrics.findDashboardCompletedMetrics(any(), any(), any()))
                .thenReturn(List.of(metric(901L, 801L, session.getMeeting(), 502L,
                        "{\"agendas\":[{\"title\":\"이전 안건\",\"durationMs\":9000}]}",
                        9000L)));

        // when
        DashboardResponse response = service.getDashboard(1L, 2L, FROM, TO);

        // then
        assertThat(response.summary().totalContextOutSeconds()).isEqualByComparingTo("0");
        assertThat(response.summary().contextOutRate()).isEqualByComparingTo("0.00");
        assertThat(response.summary().contextOutMeetingDurationSeconds()).isZero();
        assertThat(response.topics()).isEmpty();
    }

    @Test
    @DisplayName("안건은 동일 이름도 합산하지 않고 논의시간 내림차순 Top 5만 반환한다")
    void topicsKeepDuplicateNamesAndReturnTopFiveByDuration() {
        // 테스트 목적:
        // Metric JSON의 agendas를 회의별 개별 항목으로 취급해 같은 이름도 합산하지 않고
        // durationMs를 초 단위로 변환한 뒤 상위 5개만 정렬해 반환하는지 검증한다.

        // given
        RecordingSession first = session(101L, 701L,
                LocalDateTime.of(2026, 10, 12, 10, 0),
                LocalDateTime.of(2026, 10, 12, 10, 2),
                2);
        RecordingSession second = session(102L, 702L,
                LocalDateTime.of(2026, 10, 12, 11, 0),
                LocalDateTime.of(2026, 10, 12, 11, 2),
                2);
        when(recordings.findDashboardCompletedSessions(any(), any(), any())).thenReturn(List.of(first, second));
        when(summaries.findLatestCompletedSummaryIdsByMeetingIds(List.of(101L, 102L)))
                .thenReturn(List.of(new LatestSummaryRow(101L, 502L), new LatestSummaryRow(102L, 503L)));
        when(metrics.findDashboardCompletedMetrics(any(), any(), any()))
                .thenReturn(List.of(
                        metric(901L, 801L, first.getMeeting(), 502L,
                                "{\"agendas\":["
                                        + "{\"title\":\"중복\",\"durationMs\":1500},"
                                        + "{\"title\":\"큰 안건\",\"durationMs\":9000},"
                                        + "{\"title\":\"작은 안건\",\"durationMs\":500}"
                                        + "]}",
                                0L),
                        metric(902L, 802L, second.getMeeting(), 503L,
                                "{\"agendas\":["
                                        + "{\"title\":\"중복\",\"durationMs\":8000},"
                                        + "{\"title\":\"보통 안건\",\"durationMs\":3000},"
                                        + "{\"title\":\"0초 안건\",\"durationMs\":0}"
                                        + "]}",
                                0L)
                ));

        // when
        DashboardResponse response = service.getDashboard(1L, 2L, FROM, TO);

        // then
        assertThat(response.topics()).hasSize(5);
        assertThat(response.topics()).extracting("name")
                .containsExactly("큰 안건", "중복", "보통 안건", "중복", "작은 안건");
        assertThat(response.topics().get(3).durationSeconds()).isEqualByComparingTo("1.5");
        assertThat(response.topics()).extracting("name").doesNotContain("0초 안건");
    }

    @Test
    @DisplayName("contextOutDurationMs null은 0과 구분해 비율 분모에서 제외한다")
    void contextOutNullIsNotTreatedAsZero() {
        // 테스트 목적:
        // AI가 맥락 이탈 시간을 null로 응답한 회의는 알 수 없는 값으로 보고
        // 명시적인 0ms 응답과 달리 contextOutRate 분모에 포함하지 않는지 검증한다.

        // given
        RecordingSession nullContext = session(101L, 701L,
                LocalDateTime.of(2026, 10, 12, 10, 0),
                LocalDateTime.of(2026, 10, 12, 10, 2),
                2);
        RecordingSession zeroContext = session(102L, 702L,
                LocalDateTime.of(2026, 10, 12, 11, 0),
                LocalDateTime.of(2026, 10, 12, 11, 2),
                2);
        RecordingSession positiveContext = session(103L, 703L,
                LocalDateTime.of(2026, 10, 12, 12, 0),
                LocalDateTime.of(2026, 10, 12, 12, 2),
                2);
        when(recordings.findDashboardCompletedSessions(any(), any(), any()))
                .thenReturn(List.of(nullContext, zeroContext, positiveContext));
        when(summaries.findLatestCompletedSummaryIdsByMeetingIds(List.of(101L, 102L, 103L)))
                .thenReturn(List.of(new LatestSummaryRow(101L, 502L), new LatestSummaryRow(102L, 503L),
                        new LatestSummaryRow(103L, 504L)));
        when(metrics.findDashboardCompletedMetrics(any(), any(), any()))
                .thenReturn(List.of(
                        metric(901L, 801L, nullContext.getMeeting(), 502L, "{\"agendas\":null}", null),
                        metric(902L, 802L, zeroContext.getMeeting(), 503L, "{\"agendas\":[]}", 0L),
                        metric(903L, 803L, positiveContext.getMeeting(), 504L, "{\"agendas\":[]}", 60_000L)
                ));

        // when
        DashboardResponse response = service.getDashboard(1L, 2L, FROM, TO);

        // then
        assertThat(response.summary().totalContextOutSeconds()).isEqualByComparingTo("60");
        assertThat(response.summary().contextOutRate()).isEqualByComparingTo("25.00");
        assertThat(response.summary().contextOutMeetingDurationSeconds()).isEqualTo(240L);
    }

    @ParameterizedTest(name = "{1} 사용자는 대시보드 접근이 거부된다")
    @CsvSource({"3, 타 팀", "4, 탈퇴", "5, 강퇴"})
    @DisplayName("타 팀 사용자나 탈퇴·차단 상태 사용자는 대시보드를 조회할 수 없다")
    void rejectsNonActiveTeamMember(long userId, String membershipCase) {
        // 테스트 목적:
        // 요청 사용자가 해당 팀의 ACTIVE 멤버가 아닌 경우
        // 타 팀 데이터와 탈퇴·강퇴 사용자의 접근이 차단되는지 검증한다.

        // given
        when(members.existsByTeamIdAndUserIdAndMembershipStatus(2L, userId, MembershipStatus.ACTIVE))
                .thenReturn(false);

        // when, then
        assertThatThrownBy(() -> service.getDashboard(userId, 2L, FROM, TO))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_ACCESS_DENIED));
        verifyNoInteractions(recordings, summaries, metrics);
    }

    @Test
    @DisplayName("없는 팀의 대시보드는 조회할 수 없다")
    void rejectsMissingTeam() {
        // 테스트 목적:
        // 삭제되었거나 존재하지 않는 팀의 대시보드 요청은
        // 권한 검증 전에 팀 없음 오류로 거부되는지 검증한다.

        // given
        when(teams.findByIdAndDeletedAtIsNull(2L)).thenReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> service.getDashboard(1L, 2L, FROM, TO))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_NOT_FOUND));
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-12, 2026-10-13",
            "2026-10-12, 2026-10-14",
            "2026-10-12, 2026-10-15",
            "2026-10-12, 2026-10-16",
            "2026-10-12, 2026-10-17",
            "2026-10-13, 2026-10-19",
            "2026-10-11, 2026-10-17",
            "2026-10-12, 2026-10-19",
            "2026-10-12, 2026-10-25",
            "2026-10-18, 2026-10-12"
    })
    @DisplayName("하루나 같은 주 월~일 범위가 아닌 기간은 조회 전에 거절한다")
    void rejectsInvalidPeriods(LocalDate from, LocalDate to) {
        // 테스트 목적:
        // 2~6일, 월~일이 아닌 7일, 8일 이상 및 역전된 기간은
        // 잘못된 집계가 실행되기 전에 도메인 오류로 거절되는지 검증한다.

        // given

        // when, then
        assertThatThrownBy(() -> service.getDashboard(1L, 2L, from, to))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DashboardErrorCode.INVALID_DASHBOARD_PERIOD));
        verify(recordings, never()).findDashboardCompletedSessions(any(), any(), any());
    }

    @Test
    @DisplayName("일간 회의 목록은 회의 시작 시각과 회의 ID 순으로 안정적으로 정렬한다")
    void dailyMeetingsAreSortedByMeetingStartAndId() {
        // 테스트 목적:
        // 일간 회의가 제목·예정·실제 시간을 포함하고
        // 녹음 세션 순서가 아닌 회의 시작 시각과 ID 순으로 반환되는지 검증한다.

        // given
        RecordingSession later = session(103L, 703L, FROM.atTime(11, 0), FROM.atTime(11, 2), 3);
        RecordingSession tiedHigherId = session(102L, 702L, FROM.atTime(10, 0), FROM.atTime(10, 3), 4);
        RecordingSession tiedLowerId = session(101L, 701L, FROM.atTime(10, 1), FROM.atTime(10, 2), 2);
        ReflectionTestUtils.setField(tiedLowerId.getMeeting(), "startedAt", FROM.atTime(10, 0));
        when(recordings.findDashboardCompletedSessions(any(), any(), any()))
                .thenReturn(List.of(later, tiedHigherId, tiedLowerId));

        // when
        DashboardResponse response = service.getDashboard(1L, 2L, FROM, FROM);

        // then
        assertThat(response.chart()).hasSize(1);
        assertThat(response.meetings()).extracting("meetingId").containsExactly(101L, 102L, 103L);
        assertThat(response.meetings()).extracting("title").containsExactly("회의 101", "회의 102", "회의 103");
        assertThat(response.meetings()).extracting("targetDurationSeconds").containsExactly(120L, 240L, 180L);
        assertThat(response.meetings()).extracting("actualDurationSeconds").containsExactly(60L, 180L, 120L);
        assertThat(response.summary().totalMeetings()).isEqualTo(3L);
        assertThat(response.summary().totalMeetingDurationSeconds()).isEqualTo(360L);
        assertThat(response.chart().getFirst().meetingCount()).isEqualTo(3L);
        assertThat(response.chart().getFirst().actualDurationSeconds()).isEqualTo(360L);
    }

    @Test
    @DisplayName("자정을 넘긴 회의와 녹음 종료일이 다른 회의는 회의 종료일에 집계한다")
    void groupsByMeetingEndDateRatherThanRecordingDate() {
        // 테스트 목적:
        // 전날 시작한 회의 및 녹음이 먼저 끝난 회의도
        // 회의 종료 날짜의 요약·차트·회의 목록에 동일하게 포함되는지 검증한다.

        // given
        RecordingSession overnight = session(101L, 701L, FROM.minusDays(1).atTime(23, 50), FROM.atTime(0, 10), 30);
        RecordingSession earlierRecordingEnd = session(102L, 702L,
                FROM.minusDays(1).atTime(23, 30), FROM.minusDays(1).atTime(23, 40), 20);
        earlierRecordingEnd.getMeeting().complete(FROM.atTime(1, 0));
        when(recordings.findDashboardCompletedSessions(any(), any(), any()))
                .thenReturn(List.of(overnight, earlierRecordingEnd));

        // when
        DashboardResponse response = service.getDashboard(1L, 2L, FROM, FROM);

        // then
        verify(recordings).findDashboardCompletedSessions(2L, FROM.atStartOfDay(), FROM.plusDays(1).atStartOfDay());
        assertThat(response.summary().totalMeetings()).isEqualTo(2L);
        assertThat(response.summary().totalMeetingDurationSeconds()).isEqualTo(1800L);
        assertThat(response.chart().getFirst().meetingCount()).isEqualTo(2L);
        assertThat(response.chart().getFirst().actualDurationSeconds()).isEqualTo(1800L);
        assertThat(response.meetings()).hasSize(2);
    }

    @Test
    @DisplayName("주간 합계는 일별 집계와 일치하고 합산 시간이 같아도 개별 초과 회의를 센다")
    void weeklySummaryMatchesDailyPointsAndCountsIndividualOvertime() {
        // 테스트 목적:
        // 초과 회의와 미달 회의의 합계가 같아도 개별 초과를 보존하고
        // 목표시간과 같은 회의는 제외하며 주간 요약과 일별 차트의 합계가 일치하는지 검증한다.

        // given
        RecordingSession overtime = session(101L, 701L, FROM.atTime(10, 0), FROM.atTime(10, 1, 30), 1);
        RecordingSession undertime = session(102L, 702L, FROM.atTime(11, 0), FROM.atTime(11, 0, 30), 1);
        RecordingSession exact = session(103L, 703L, FROM.plusDays(1).atTime(10, 0), FROM.plusDays(1).atTime(10, 1), 1);
        when(recordings.findDashboardCompletedSessions(any(), any(), any()))
                .thenReturn(List.of(overtime, undertime, exact));

        // when
        DashboardResponse response = service.getDashboard(1L, 2L, FROM, TO);

        // then
        assertThat(response.chart()).hasSize(7);
        assertThat(response.meetings()).isEmpty();
        assertThat(response.chart().getFirst().targetDurationSeconds()).isEqualTo(120L);
        assertThat(response.chart().getFirst().actualDurationSeconds()).isEqualTo(120L);
        assertThat(response.chart().getFirst().overtimeMeetings()).isEqualTo(1L);
        assertThat(response.chart().get(1).overtimeMeetings()).isZero();
        assertThat(response.chart().stream().mapToLong(DashboardChartPointResponse::meetingCount).sum())
                .isEqualTo(response.summary().totalMeetings()).isEqualTo(3L);
        assertThat(response.chart().stream().mapToLong(DashboardChartPointResponse::actualDurationSeconds).sum())
                .isEqualTo(response.summary().totalMeetingDurationSeconds()).isEqualTo(180L);
        assertThat(response.chart().stream().mapToLong(DashboardChartPointResponse::targetDurationSeconds).sum())
                .isEqualTo(180L);
        assertThat(response.chart().stream().mapToLong(DashboardChartPointResponse::overtimeMeetings).sum())
                .isEqualTo(response.summary().overtimeMeetings()).isEqualTo(1L);
        assertThat(response.summary().overtimeMeetingRate()).isEqualByComparingTo("33.33");
        assertThat(response.chart().subList(2, 7)).allSatisfy(point -> {
            assertThat(point.meetingCount()).isZero();
            assertThat(point.targetDurationSeconds()).isZero();
            assertThat(point.actualDurationSeconds()).isZero();
            assertThat(point.overtimeMeetings()).isZero();
        });
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-12T14:59:59Z, 2026-10-12",
            "2026-10-12T15:00:00Z, 2026-10-13"
    })
    @DisplayName("서버 Clock이 UTC여도 KST 자정 기준 오늘까지 조회하고 미래 날짜는 0으로 채운다")
    void currentWeekUsesKstDateAndKeepsFuturePointsEmpty(Instant instant, LocalDate today) {
        // 테스트 목적:
        // UTC 서버에서도 KST 날짜가 바뀌는 순간 조회 상한이 다음 날짜로 바뀌고
        // 주간 응답은 미래 날짜까지 7개를 유지하되 미래 집계값은 모두 0인지 검증한다.

        // given
        DashboardService kstService = serviceWithClock(Clock.fixed(instant, ZoneId.of("UTC")));
        RecordingSession completedToday = session(101L, 701L, today.atTime(0, 0), today.atTime(0, 1), 1);
        when(recordings.findDashboardCompletedSessions(any(), any(), any())).thenReturn(List.of(completedToday));
        when(summaries.findLatestCompletedSummaryIdsByMeetingIds(List.of(101L)))
                .thenReturn(List.of(new LatestSummaryRow(101L, 502L)));

        // when
        DashboardResponse response = kstService.getDashboard(1L, 2L, FROM, TO);

        // then
        verify(recordings).findDashboardCompletedSessions(2L, FROM.atStartOfDay(), today.plusDays(1).atStartOfDay());
        verify(metrics).findDashboardCompletedMetrics(2L, FROM.atStartOfDay(), today.plusDays(1).atStartOfDay());
        assertThat(response.endDate()).isEqualTo(TO);
        assertThat(response.chart()).hasSize(7);
        assertThat(response.summary().totalMeetings()).isEqualTo(1L);
        assertThat(response.chart().stream().filter(point -> point.startDate().equals(today)).findFirst().orElseThrow()
                .meetingCount()).isEqualTo(1L);
        assertThat(response.chart().stream().filter(point -> point.startDate().isAfter(today))).allSatisfy(point -> {
            assertThat(point.meetingCount()).isZero();
            assertThat(point.targetDurationSeconds()).isZero();
            assertThat(point.actualDurationSeconds()).isZero();
            assertThat(point.overtimeMeetings()).isZero();
        });
    }

    @ParameterizedTest
    @CsvSource({"2026-10-19, 2026-10-19, 1", "2026-10-19, 2026-10-25, 7"})
    @DisplayName("형식이 올바른 전체 미래 기간은 권한 검증 후 0 집계와 빈 목록을 반환한다")
    void entirelyFuturePeriodReturnsZeros(LocalDate from, LocalDate to, int days) {
        // 테스트 목적:
        // 미래 일간·주간도 허용된 날짜 범위이면 조회할 수 있고
        // 미래 데이터 조회 없이 동일한 응답 구조와 0 집계가 반환되는지 검증한다.

        // given

        // when
        DashboardResponse response = service.getDashboard(1L, 2L, from, to);

        // then
        assertThat(response.periodDays()).isEqualTo(days);
        assertThat(response.chart()).hasSize(days).allSatisfy(point -> {
            assertThat(point.meetingCount()).isZero();
            assertThat(point.targetDurationSeconds()).isZero();
            assertThat(point.actualDurationSeconds()).isZero();
            assertThat(point.overtimeMeetings()).isZero();
        });
        assertThat(response.summary().totalMeetings()).isZero();
        assertThat(response.summary().totalMeetingDurationSeconds()).isZero();
        assertThat(response.meetings()).isEmpty();
        assertThat(response.topics()).isEmpty();
        verify(members).existsByTeamIdAndUserIdAndMembershipStatus(2L, 1L, MembershipStatus.ACTIVE);
        verifyNoInteractions(recordings, summaries, metrics);
    }

    @Test
    @DisplayName("평균 회의시간은 기존 초 단위 올림 정책을 유지한다")
    void averageDurationKeepsCeilingPolicy() {
        // 테스트 목적:
        // 실제 회의시간 평균이 정수 초로 나누어떨어지지 않을 때
        // 기존 올림 정책을 유지하는지 검증한다.

        // given
        when(recordings.findDashboardCompletedSessions(any(), any(), any())).thenReturn(List.of(
                session(101L, 701L, FROM.atTime(10, 0), FROM.atTime(10, 0, 1), 1),
                session(102L, 702L, FROM.atTime(11, 0), FROM.atTime(11, 0, 2), 1)));

        // when
        DashboardResponse response = service.getDashboard(1L, 2L, FROM, FROM);

        // then
        assertThat(response.summary().totalMeetingDurationSeconds()).isEqualTo(3L);
        assertThat(response.summary().averageMeetingDurationSeconds()).isEqualTo(2L);
    }

    @Test
    @DisplayName("안건의 미분석 null 시간은 제외하고 정상 분석된 0초 안건은 유지한다")
    void topicsDistinguishNullDurationFromZero() {
        // 테스트 목적:
        // 안건 논의시간이 null이면 집계에서 제외하지만
        // 명시적인 0ms 분석 결과는 유효한 0초 항목으로 반환하는지 검증한다.

        // given
        RecordingSession completed = session(101L, 701L, FROM.atTime(10, 0), FROM.atTime(10, 2), 2);
        when(recordings.findDashboardCompletedSessions(any(), any(), any())).thenReturn(List.of(completed));
        when(summaries.findLatestCompletedSummaryIdsByMeetingIds(List.of(101L)))
                .thenReturn(List.of(new LatestSummaryRow(101L, 502L)));
        when(metrics.findDashboardCompletedMetrics(any(), any(), any())).thenReturn(List.of(
                metric(901L, 801L, completed.getMeeting(), 502L,
                        "{\"agendas\":[{\"title\":\"미분석\",\"durationMs\":null},"
                                + "{\"title\":\"분석됨\",\"durationMs\":1501},{\"title\":\"0초\",\"durationMs\":0}]}", 0L)));

        // when
        DashboardResponse response = service.getDashboard(1L, 2L, FROM, FROM);

        // then
        assertThat(response.topics()).extracting("name").containsExactly("분석됨", "0초");
        assertThat(response.topics().getFirst().durationSeconds()).isEqualByComparingTo("1.501");
        assertThat(response.topics().getLast().durationSeconds()).isEqualByComparingTo("0");
    }

    private DashboardService serviceWithClock(Clock clock) {
        return new DashboardService(teams, members, recordings, summaries, metrics, new ObjectMapper(), clock);
    }

    private RecordingSession session(
            Long meetingId,
            Long sessionId,
            LocalDateTime startedAt,
            LocalDateTime endedAt,
            int targetDurationMinutes
    ) {
        Meeting meeting = withId(Meeting.create(
                team, member, "회의 " + meetingId, "목적", null, startedAt, targetDurationMinutes), meetingId);
        meeting.start(startedAt);
        RecordingSession session = withId(RecordingSession.start(meeting, member, startedAt), sessionId);
        session.complete(endedAt);
        meeting.complete(endedAt);
        return session;
    }

    private MeetingMetric metric(
            Long metricId,
            Long aiRequestId,
            Meeting meeting,
            Long summaryId,
            String metricsJson,
            Long contextOutDurationMs
    ) {
        AiRequest aiRequest = withId(AiRequest.create(
                team, member, MetricPolicy.idempotencyKey(summaryId), AiRequestType.METRIC), aiRequestId);
        aiRequest.markCompleted();
        return withId(MeetingMetric.create(aiRequest, meeting, metricsJson, contextOutDurationMs), metricId);
    }

    private record LatestSummaryRow(Long meetingId, Long summaryId) implements LatestCompletedSummaryProjection {

        @Override
        public Long getMeetingId() {
            return meetingId;
        }

        @Override
        public Long getSummaryId() {
            return summaryId;
        }
    }
}
