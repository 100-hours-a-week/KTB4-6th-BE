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
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.dashboard.dto.DashboardResponse;
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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DashboardServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneId.of("Asia/Seoul"));

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

    @Test
    @DisplayName("7·14·21·28일 기간은 KST 오늘 포함 범위와 7개 차트 구간을 반환한다")
    void supportedPeriodsReturnSevenChartBuckets() {
        // 테스트 목적:
        // 지원하는 모든 대시보드 기간이 KST 기준 오늘을 포함해 시작일과 종료일을 계산하고
        // 선택 기간을 항상 7개 구간으로 나누는지 검증한다.

        // given, when, then
        assertPeriod(7, "2026-10-06", "2026-10-12", "2026-10-06", "2026-10-06");
        assertPeriod(14, "2026-09-29", "2026-10-12", "2026-09-29", "2026-09-30");
        assertPeriod(21, "2026-09-22", "2026-10-12", "2026-09-22", "2026-09-24");
        assertPeriod(28, "2026-09-15", "2026-10-12", "2026-09-15", "2026-09-18");
    }

    @Test
    @DisplayName("회의가 없는 기간은 0 요약과 빈 구간 7개를 반환한다")
    void emptyPeriodReturnsZeroSummaryAndSevenEmptyBuckets() {
        // 테스트 목적:
        // 조회 기간에 완료 회의와 Metric 데이터가 전혀 없어도
        // 오류 없이 0으로 채운 요약과 7개 차트 구간을 반환하는지 검증한다.

        // given

        // when
        DashboardResponse response = service.getDashboard(1L, 2L, 28);

        // then
        assertThat(response.summary().totalMeetings()).isZero();
        assertThat(response.summary().totalMeetingDurationSeconds()).isZero();
        assertThat(response.summary().averageMeetingDurationSeconds()).isZero();
        assertThat(response.summary().overtimeMeetingRate()).isEqualByComparingTo("0.00");
        assertThat(response.summary().contextOutRate()).isEqualByComparingTo("0.00");
        assertThat(response.chart()).hasSize(7);
        assertThat(response.chart())
                .allSatisfy(point -> {
                    assertThat(point.targetDurationSeconds()).isZero();
                    assertThat(point.actualDurationSeconds()).isZero();
                });
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
        when(recordings.findDashboardCompletedSessions(any(), any(), any()))
                .thenReturn(List.of(first, second));

        // when
        DashboardResponse response = service.getDashboard(1L, 2L, 7);

        // then
        assertThat(response.summary().totalMeetings()).isEqualTo(2L);
        assertThat(response.summary().totalMeetingDurationSeconds()).isEqualTo(420L);
        assertThat(response.summary().averageMeetingDurationSeconds()).isEqualTo(210L);
        assertThat(response.chart().getLast().targetDurationSeconds()).isEqualTo(300L);
        assertThat(response.chart().getLast().actualDurationSeconds()).isEqualTo(420L);
    }

    @Test
    @DisplayName("초과 회의 수와 시간 초과율은 초과분 합계 기준으로 가중 집계한다")
    void aggregatesOvertimeRatesByWeightedDuration() {
        // 테스트 목적:
        // 예정시간이 다른 회의들이 섞여 있을 때
        // 시간 초과율을 회의별 초과율 평균이 아니라 초과시간 합계와 예정시간 합계로 계산하는지 검증한다.

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
        DashboardResponse response = service.getDashboard(1L, 2L, 7);

        // then
        assertThat(response.summary().overtimeMeetings()).isEqualTo(2L);
        assertThat(response.summary().overtimeMeetingRate()).isEqualByComparingTo("66.67");
        assertThat(response.summary().overtimeDurationRate()).isEqualByComparingTo("83.33");
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
        DashboardResponse response = service.getDashboard(1L, 2L, 7);

        // then
        assertThat(response.summary().totalMeetings()).isEqualTo(1L);
        assertThat(response.summary().totalMeetingDurationSeconds()).isEqualTo(120L);
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
        DashboardResponse response = service.getDashboard(1L, 2L, 7);

        // then
        assertThat(response.summary().totalContextOutSeconds()).isEqualByComparingTo("1.5");
        assertThat(response.summary().contextOutRate()).isEqualByComparingTo("1.25");
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
        DashboardResponse response = service.getDashboard(1L, 2L, 7);

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
        DashboardResponse response = service.getDashboard(1L, 2L, 7);

        // then
        assertThat(response.summary().totalContextOutSeconds()).isEqualByComparingTo("0");
        assertThat(response.summary().contextOutRate()).isEqualByComparingTo("0.00");
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
        DashboardResponse response = service.getDashboard(1L, 2L, 7);

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
        when(recordings.findDashboardCompletedSessions(any(), any(), any()))
                .thenReturn(List.of(nullContext, zeroContext));
        when(summaries.findLatestCompletedSummaryIdsByMeetingIds(List.of(101L, 102L)))
                .thenReturn(List.of(new LatestSummaryRow(101L, 502L), new LatestSummaryRow(102L, 503L)));
        when(metrics.findDashboardCompletedMetrics(any(), any(), any()))
                .thenReturn(List.of(
                        metric(901L, 801L, nullContext.getMeeting(), 502L, "{\"agendas\":null}", null),
                        metric(902L, 802L, zeroContext.getMeeting(), 503L, "{\"agendas\":[]}", 0L)
                ));

        // when
        DashboardResponse response = service.getDashboard(1L, 2L, 7);

        // then
        assertThat(response.summary().totalContextOutSeconds()).isEqualByComparingTo("0");
        assertThat(response.summary().contextOutRate()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("타 팀 사용자나 탈퇴·차단 상태 사용자는 대시보드를 조회할 수 없다")
    void rejectsNonActiveTeamMember() {
        // 테스트 목적:
        // 요청 사용자가 해당 팀의 ACTIVE 멤버가 아닌 경우
        // 타 팀 데이터와 탈퇴·강퇴 사용자의 접근이 차단되는지 검증한다.

        // given
        when(members.existsByTeamIdAndUserIdAndMembershipStatus(2L, 1L, MembershipStatus.ACTIVE))
                .thenReturn(false);

        // when, then
        assertThatThrownBy(() -> service.getDashboard(1L, 2L, 28))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_ACCESS_DENIED));
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
        assertThatThrownBy(() -> service.getDashboard(1L, 2L, 28))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_NOT_FOUND));
    }

    @Test
    @DisplayName("days가 7, 14, 21, 28이 아니면 400 오류로 거부한다")
    void rejectsInvalidDays() {
        // 테스트 목적:
        // 대시보드 기간은 합의된 7, 14, 21, 28일만 지원하므로
        // 그 외 값이 서비스 조회로 진행되지 않는지 검증한다.

        // given, when, then
        assertThatThrownBy(() -> service.getDashboard(1L, 2L, 13))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DashboardErrorCode.INVALID_DASHBOARD_PERIOD));
        verify(recordings, never()).findDashboardCompletedSessions(any(), any(), any());
    }

    private void assertPeriod(
            int days,
            String expectedStartDate,
            String expectedEndDate,
            String expectedFirstBucketStartDate,
            String expectedFirstBucketEndDate
    ) {
        DashboardResponse response = service.getDashboard(1L, 2L, days);
        assertThat(response.periodDays()).isEqualTo(days);
        assertThat(response.startDate()).hasToString(expectedStartDate);
        assertThat(response.endDate()).hasToString(expectedEndDate);
        assertThat(response.chart()).hasSize(7);
        assertThat(response.chart().getFirst().startDate()).hasToString(expectedFirstBucketStartDate);
        assertThat(response.chart().getFirst().endDate()).hasToString(expectedFirstBucketEndDate);
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
