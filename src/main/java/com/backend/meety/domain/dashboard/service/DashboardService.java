package com.backend.meety.domain.dashboard.service;

import com.backend.meety.domain.dashboard.dto.DashboardChartPointResponse;
import com.backend.meety.domain.dashboard.dto.DashboardResponse;
import com.backend.meety.domain.dashboard.dto.DashboardSummaryResponse;
import com.backend.meety.domain.dashboard.dto.DashboardTopicResponse;
import com.backend.meety.domain.dashboard.exception.DashboardErrorCode;
import com.backend.meety.domain.dashboard.exception.DashboardException;
import com.backend.meety.domain.meeting.MetricPolicy;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingMetric;
import com.backend.meety.domain.meeting.repository.MeetingMetricRepository;
import com.backend.meety.domain.meeting.repository.MeetingSummaryRepository;
import com.backend.meety.domain.meeting.repository.MeetingSummaryRepository.LatestCompletedSummaryProjection;
import com.backend.meety.domain.recording.entity.RecordingSession;
import com.backend.meety.domain.recording.repository.RecordingSessionRepository;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.exception.TeamErrorCode;
import com.backend.meety.domain.team.exception.TeamException;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.domain.team.repository.TeamRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final ZoneId KST_ZONE_ID = ZoneId.of("Asia/Seoul");
    private static final Set<Integer> SUPPORTED_PERIOD_DAYS = Set.of(7, 14, 21, 28);
    private static final int CHART_BUCKET_COUNT = 7;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal ONE_THOUSAND = BigDecimal.valueOf(1000);

    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final RecordingSessionRepository recordingSessionRepository;
    private final MeetingSummaryRepository meetingSummaryRepository;
    private final MeetingMetricRepository meetingMetricRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional(readOnly = true)
    public DashboardResponse getDashboard(Long userId, Long teamId, Integer days) {
        DashboardPeriod period = resolvePeriod(days);
        validateTeamAccess(userId, teamId);

        List<DashboardMeetingDuration> durations = deduplicateByMeeting(
                recordingSessionRepository.findDashboardCompletedSessions(
                        teamId, period.startDateTime(), period.endExclusiveDateTime()));
        Map<Long, DashboardMeetingDuration> durationByMeetingId = durations.stream()
                .collect(Collectors.toMap(DashboardMeetingDuration::meetingId, duration -> duration));
        Map<Long, MeetingMetric> latestMetrics = findLatestMetrics(
                teamId, period, new ArrayList<>(durationByMeetingId.keySet()));

        DashboardSummaryResponse summary = buildSummary(durations, latestMetrics, durationByMeetingId);
        List<DashboardChartPointResponse> chart = buildChart(period, durations);
        List<DashboardTopicResponse> topics = buildTopics(latestMetrics.values().stream().toList());

        return new DashboardResponse(
                period.days(),
                period.startDate(),
                period.endDate(),
                summary,
                chart,
                topics
        );
    }

    private DashboardPeriod resolvePeriod(Integer days) {
        if (days == null || !SUPPORTED_PERIOD_DAYS.contains(days)) {
            throw new DashboardException(DashboardErrorCode.INVALID_DASHBOARD_PERIOD);
        }
        LocalDate endDate = LocalDate.now(clock.withZone(KST_ZONE_ID));
        LocalDate startDate = endDate.minusDays(days - 1L);
        return new DashboardPeriod(days, startDate, endDate);
    }

    private void validateTeamAccess(Long userId, Long teamId) {
        if (teamRepository.findByIdAndDeletedAtIsNull(teamId).isEmpty()) {
            throw new TeamException(TeamErrorCode.TEAM_NOT_FOUND);
        }
        if (!teamMemberRepository.existsByTeamIdAndUserIdAndMembershipStatus(
                teamId, userId, MembershipStatus.ACTIVE)) {
            throw new TeamException(TeamErrorCode.TEAM_ACCESS_DENIED);
        }
    }

    private List<DashboardMeetingDuration> deduplicateByMeeting(List<RecordingSession> sessions) {
        Map<Long, DashboardMeetingDuration> durations = new LinkedHashMap<>();
        for (RecordingSession session : sessions) {
            Meeting meeting = session.getMeeting();
            durations.putIfAbsent(meeting.getId(), DashboardMeetingDuration.from(session));
        }
        return new ArrayList<>(durations.values());
    }

    private Map<Long, MeetingMetric> findLatestMetrics(
            Long teamId,
            DashboardPeriod period,
            List<Long> meetingIds
    ) {
        if (meetingIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> latestSummaryIds = meetingSummaryRepository
                .findLatestCompletedSummaryIdsByMeetingIds(meetingIds).stream()
                .collect(Collectors.toMap(
                        LatestCompletedSummaryProjection::getMeetingId,
                        LatestCompletedSummaryProjection::getSummaryId
                ));
        if (latestSummaryIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, MeetingMetric> metricsByMeetingId = new LinkedHashMap<>();
        for (MeetingMetric metric : meetingMetricRepository.findDashboardCompletedMetrics(
                teamId, period.startDateTime(), period.endExclusiveDateTime())) {
            Long meetingId = metric.getMeeting().getId();
            Long latestSummaryId = latestSummaryIds.get(meetingId);
            if (latestSummaryId == null || !latestSummaryId.equals(metricSummaryId(metric))) {
                continue;
            }
            metricsByMeetingId.putIfAbsent(meetingId, metric);
        }
        return metricsByMeetingId;
    }

    private Long metricSummaryId(MeetingMetric metric) {
        try {
            return MetricPolicy.summaryIdOf(metric.getAiRequest().getIdempotencyKey());
        } catch (RuntimeException e) {
            log.warn("Metric 멱등키에서 Summary ID를 확인할 수 없습니다. metricId={}, aiRequestId={}",
                    metric.getId(), metric.getAiRequest().getId(), e);
            return null;
        }
    }

    private DashboardSummaryResponse buildSummary(
            List<DashboardMeetingDuration> durations,
            Map<Long, MeetingMetric> latestMetrics,
            Map<Long, DashboardMeetingDuration> durationByMeetingId
    ) {
        long totalMeetings = durations.size();
        long totalMeetingDurationSeconds = durations.stream()
                .mapToLong(DashboardMeetingDuration::actualDurationSeconds)
                .sum();
        long averageMeetingDurationSeconds = totalMeetings == 0
                ? 0L
                : Math.ceilDiv(totalMeetingDurationSeconds, totalMeetings);
        List<DashboardMeetingDuration> overtimeDurations = durations.stream()
                .filter(DashboardMeetingDuration::isOvertime)
                .toList();
        long overtimeMeetings = overtimeDurations.size();
        long overtimeDurationSeconds = overtimeDurations.stream()
                .mapToLong(DashboardMeetingDuration::overtimeDurationSeconds)
                .sum();
        long overtimeTargetDurationSeconds = overtimeDurations.stream()
                .mapToLong(DashboardMeetingDuration::targetDurationSeconds)
                .sum();

        MetricContextOutAggregate contextOut = aggregateContextOut(latestMetrics, durationByMeetingId);
        return new DashboardSummaryResponse(
                totalMeetings,
                totalMeetingDurationSeconds,
                averageMeetingDurationSeconds,
                overtimeMeetings,
                percentage(overtimeMeetings, totalMeetings),
                percentage(overtimeDurationSeconds, overtimeTargetDurationSeconds),
                msToSeconds(contextOut.contextOutDurationMs()),
                percentage(contextOut.contextOutDurationMs(), contextOut.meetingDurationMs())
        );
    }

    private MetricContextOutAggregate aggregateContextOut(
            Map<Long, MeetingMetric> latestMetrics,
            Map<Long, DashboardMeetingDuration> durationByMeetingId
    ) {
        long contextOutDurationMs = 0L;
        long meetingDurationMs = 0L;
        for (Map.Entry<Long, MeetingMetric> entry : latestMetrics.entrySet()) {
            Long contextOut = entry.getValue().getContextOutDurationMs();
            if (contextOut == null) {
                continue;
            }
            DashboardMeetingDuration duration = durationByMeetingId.get(entry.getKey());
            if (duration == null) {
                continue;
            }
            contextOutDurationMs += contextOut;
            meetingDurationMs += duration.actualDurationMs();
        }
        return new MetricContextOutAggregate(contextOutDurationMs, meetingDurationMs);
    }

    private List<DashboardChartPointResponse> buildChart(
            DashboardPeriod period,
            List<DashboardMeetingDuration> durations
    ) {
        int bucketDays = period.days() / CHART_BUCKET_COUNT;
        List<DashboardChartPointResponse> chart = new ArrayList<>();
        for (int i = 0; i < CHART_BUCKET_COUNT; i++) {
            LocalDate bucketStart = period.startDate().plusDays((long) i * bucketDays);
            LocalDate bucketEnd = bucketStart.plusDays(bucketDays - 1L);
            long targetDurationSeconds = 0L;
            long actualDurationSeconds = 0L;
            for (DashboardMeetingDuration duration : durations) {
                if (duration.endedDate().isBefore(bucketStart) || duration.endedDate().isAfter(bucketEnd)) {
                    continue;
                }
                targetDurationSeconds += duration.targetDurationSeconds();
                actualDurationSeconds += duration.actualDurationSeconds();
            }
            chart.add(new DashboardChartPointResponse(
                    bucketStart, bucketEnd, targetDurationSeconds, actualDurationSeconds));
        }
        return chart;
    }

    private List<DashboardTopicResponse> buildTopics(List<MeetingMetric> metrics) {
        return metrics.stream()
                .flatMap(metric -> parseTopics(metric).stream())
                .sorted(Comparator.comparing(DashboardTopicResponse::durationSeconds).reversed())
                .limit(5)
                .toList();
    }

    private List<DashboardTopicResponse> parseTopics(MeetingMetric metric) {
        try {
            MetricPayload payload = objectMapper.readValue(metric.getMetrics(), MetricPayload.class);
            if (payload.agendas() == null) {
                return List.of();
            }
            return payload.agendas().stream()
                    .filter(agenda -> agenda.title() != null && agenda.durationMs() != null)
                    .map(agenda -> new DashboardTopicResponse(
                            agenda.title(), msToSeconds(agenda.durationMs())))
                    .toList();
        } catch (RuntimeException e) {
            log.warn("Metric JSON을 파싱할 수 없어 안건 집계에서 제외합니다. metricId={}", metric.getId(), e);
            return List.of();
        }
    }

    private BigDecimal percentage(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return BigDecimal.valueOf(numerator)
                .multiply(ONE_HUNDRED)
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal msToSeconds(long durationMs) {
        return BigDecimal.valueOf(durationMs)
                .divide(ONE_THOUSAND, 3, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    private record DashboardPeriod(Integer days, LocalDate startDate, LocalDate endDate) {

        LocalDateTime startDateTime() {
            return startDate.atStartOfDay();
        }

        LocalDateTime endExclusiveDateTime() {
            return endDate.plusDays(1).atStartOfDay();
        }
    }

    private record DashboardMeetingDuration(
            Long meetingId,
            LocalDate endedDate,
            Long targetDurationSeconds,
            Long actualDurationSeconds,
            Long actualDurationMs
    ) {

        static DashboardMeetingDuration from(RecordingSession session) {
            Meeting meeting = session.getMeeting();
            long actualDurationMs = Math.max(0L, Duration.between(
                    session.getStartedAt(), session.getEndedAt()).toMillis());
            return new DashboardMeetingDuration(
                    meeting.getId(),
                    meeting.getEndedAt().toLocalDate(),
                    meeting.getTargetDurationMinutes() * 60L,
                    actualDurationMs / 1000L,
                    actualDurationMs
            );
        }

        boolean isOvertime() {
            return actualDurationSeconds > targetDurationSeconds;
        }

        long overtimeDurationSeconds() {
            return Math.max(0L, actualDurationSeconds - targetDurationSeconds);
        }
    }

    private record MetricContextOutAggregate(Long contextOutDurationMs, Long meetingDurationMs) {
    }

    private record MetricPayload(List<MetricAgendaPayload> agendas) {
    }

    private record MetricAgendaPayload(String title, Long durationMs) {
    }
}
