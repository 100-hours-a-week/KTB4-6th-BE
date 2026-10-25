package com.backend.meety.domain.meeting.service;

import com.backend.meety.domain.ai.client.SttPrewarmAiClient;
import com.backend.meety.domain.ai.client.SttPrewarmProperties;
import com.backend.meety.domain.ai.client.SttPrewarmRequest;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.global.config.SchedulingConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

@Slf4j
@Component
public class SttPrewarmScheduler {

    private static final ZoneId KST_ZONE_ID = ZoneId.of("Asia/Seoul");

    private final MeetingRepository meetings;
    private final SttPrewarmAiClient aiClient;
    private final SttPrewarmProperties properties;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final Counter runs;
    private final Counter selectedMeetings;
    private final Counter accepted;
    private final Counter timeouts;

    public SttPrewarmScheduler(MeetingRepository meetings, SttPrewarmAiClient aiClient,
                               SttPrewarmProperties properties, Clock clock, MeterRegistry metrics) {
        this.meetings = meetings;
        this.aiClient = aiClient;
        this.properties = properties;
        this.clock = clock;
        this.metrics = metrics;
        this.runs = metrics.counter("ai.stt.prewarm.scheduler.runs");
        this.selectedMeetings = metrics.counter("ai.stt.prewarm.meetings");
        this.accepted = metrics.counter("ai.stt.prewarm.accepted");
        this.timeouts = metrics.counter("ai.stt.prewarm.timeouts");
    }

    @Scheduled(fixedDelayString = "${ai.stt.prewarm.scheduler-delay}",
            scheduler = SchedulingConfig.DEFAULT_TASK_SCHEDULER_BEAN_NAME)
    public void prewarmUpcomingMeetings() {
        runs.increment();
        LocalDateTime now = LocalDateTime.now(clock.withZone(KST_ZONE_ID));
        List<Meeting> upcoming = meetings.findWaitingForPrewarm(now, now.plus(properties.lookahead()));
        selectedMeetings.increment(upcoming.size());

        for (Meeting meeting : upcoming) {
            prewarm(meeting);
        }
    }

    private void prewarm(Meeting meeting) {
        var startsAt = meeting.getScheduledAt().atZone(KST_ZONE_ID).toOffsetDateTime();
        var request = new SttPrewarmRequest(meeting.getId(), startsAt);
        try {
            recordStatus(meeting, request, aiClient.requestPrewarm(request));
        } catch (RestClientResponseException e) {
            recordStatus(meeting, request, e.getStatusCode());
        } catch (ResourceAccessException e) {
            String reason = isTimeout(e) ? "timeout" : "transport";
            if ("timeout".equals(reason)) {
                timeouts.increment();
            }
            metrics.counter("ai.stt.prewarm.failures", "reason", reason).increment();
            log.warn("AI STT prewarm 연결에 실패했습니다. meetingId={}, scheduledAt={}, startsAt={}, reason={}",
                    meeting.getId(), meeting.getScheduledAt(), request.startsAt(), reason, e);
        }
    }

    private void recordStatus(Meeting meeting, SttPrewarmRequest request, HttpStatusCode status) {
        if (status.value() == 202) {
            accepted.increment();
            log.debug("AI STT prewarm 요청을 접수했습니다. meetingId={}, scheduledAt={}, startsAt={}, status={}",
                    meeting.getId(), meeting.getScheduledAt(), request.startsAt(), status.value());
            return;
        }

        String reason = switch (status.value()) {
            case 401 -> "unauthorized";
            case 403 -> "forbidden";
            case 422 -> "invalid_request";
            default -> status.is5xxServerError() ? "server" : "unexpected_status";
        };
        metrics.counter("ai.stt.prewarm.failures", "reason", reason).increment();
        log.warn("AI STT prewarm 요청에 실패했습니다. meetingId={}, scheduledAt={}, startsAt={}, status={}, reason={}",
                meeting.getId(), meeting.getScheduledAt(), request.startsAt(), status.value(), reason);
    }

    private boolean isTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
