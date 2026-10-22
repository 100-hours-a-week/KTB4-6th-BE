package com.backend.meety.domain.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.client.SttPrewarmAiClient;
import com.backend.meety.domain.ai.client.SttPrewarmProperties;
import com.backend.meety.domain.ai.client.SttPrewarmRequest;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;

class SttPrewarmSchedulerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T05:59:30Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW_KST = LocalDateTime.of(2026, 10, 22, 14, 59, 30);

    private MeetingRepository meetings;
    private SttPrewarmAiClient aiClient;
    private SimpleMeterRegistry meters;
    private SttPrewarmScheduler scheduler;

    @BeforeEach
    void setUp() {
        meetings = mock(MeetingRepository.class);
        aiClient = mock(SttPrewarmAiClient.class);
        meters = new SimpleMeterRegistry();
        scheduler = new SttPrewarmScheduler(meetings, aiClient,
                new SttPrewarmProperties(Duration.ofSeconds(10), Duration.ofMinutes(1), Duration.ofSeconds(3)),
                CLOCK, meters);
    }

    @Test
    @DisplayName("스케줄러는 KST 1분 범위를 조회하고 각 회의 예정 시각을 변환 없이 +09:00으로 보낸다")
    void sendsEveryUpcomingMeetingWithKstOffset() {
        // 테스트 목적:
        // UTC 서버 Clock에서도 조회 시각과 AI 전송 시각이 KST 예정 시각과 정확히 일치하는지 검증한다.
        // given
        Meeting first = meeting(1L, NOW_KST);
        Meeting second = meeting(2L, NOW_KST.plusSeconds(59));
        when(meetings.findWaitingForPrewarm(NOW_KST, NOW_KST.plusMinutes(1)))
                .thenReturn(List.of(first, second));
        when(aiClient.requestPrewarm(any())).thenReturn(HttpStatusCode.valueOf(202));

        // when
        scheduler.prewarmUpcomingMeetings();

        // then
        verify(aiClient).requestPrewarm(new SttPrewarmRequest(1L,
                NOW_KST.atOffset(ZoneOffset.ofHours(9))));
        verify(aiClient).requestPrewarm(new SttPrewarmRequest(2L,
                NOW_KST.plusSeconds(59).atOffset(ZoneOffset.ofHours(9))));
        assertThat(meters.counter("ai.stt.prewarm.scheduler.runs").count()).isEqualTo(1);
        assertThat(meters.counter("ai.stt.prewarm.meetings").count()).isEqualTo(2);
        assertThat(meters.counter("ai.stt.prewarm.accepted").count()).isEqualTo(2);
    }

    @Test
    @DisplayName("KST 자정을 넘는 예정 시각도 현지 시각과 offset을 유지한다")
    void preservesLocalClockAcrossDateBoundary() {
        // 테스트 목적:
        // UTC 날짜와 KST 날짜가 다른 경우에도 예정 시각의 날짜와 시각을 밀지 않는지 검증한다.
        // given
        LocalDateTime scheduledAt = LocalDateTime.of(2026, 10, 23, 0, 0);
        Clock boundaryClock = Clock.fixed(Instant.parse("2026-10-22T14:59:30Z"), ZoneOffset.UTC);
        scheduler = new SttPrewarmScheduler(meetings, aiClient,
                new SttPrewarmProperties(Duration.ofSeconds(10), Duration.ofMinutes(1), Duration.ofSeconds(3)),
                boundaryClock, meters);
        Meeting target = meeting(3L, scheduledAt);
        when(meetings.findWaitingForPrewarm(LocalDateTime.of(2026, 10, 22, 23, 59, 30),
                LocalDateTime.of(2026, 10, 23, 0, 0, 30))).thenReturn(List.of(target));
        when(aiClient.requestPrewarm(any())).thenReturn(HttpStatusCode.valueOf(202));

        // when
        scheduler.prewarmUpcomingMeetings();

        // then
        verify(aiClient).requestPrewarm(new SttPrewarmRequest(3L,
                scheduledAt.atOffset(ZoneOffset.ofHours(9))));
    }

    @Test
    @DisplayName("같은 WAITING 회의는 다음 주기에 다시 prewarm 요청한다")
    void repeatsSameMeetingOnEveryCycle() {
        // 테스트 목적:
        // BE에 완료 플래그나 dedupe 없이 조회되는 동안 매 주기 같은 회의를 다시 전달하는지 검증한다.
        // given
        Meeting target = meeting(1L, NOW_KST.plusSeconds(10));
        when(meetings.findWaitingForPrewarm(NOW_KST, NOW_KST.plusMinutes(1)))
                .thenReturn(List.of(target));
        when(aiClient.requestPrewarm(any())).thenReturn(HttpStatusCode.valueOf(202));

        // when
        scheduler.prewarmUpcomingMeetings();
        scheduler.prewarmUpcomingMeetings();

        // then
        verify(aiClient, times(2)).requestPrewarm(any());
        assertThat(meters.counter("ai.stt.prewarm.accepted").count()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 422, 500})
    @DisplayName("한 회의의 인증·입력·서버 오류 후에도 다음 회의의 prewarm을 계속한다")
    void isolatesHttpStatusFailure(int httpStatus) {
        // 테스트 목적:
        // 예상 가능한 AI HTTP 실패가 같은 주기의 다른 회의를 막지 않고 실패 유형별로 기록되는지 검증한다.
        // given
        Meeting first = meeting(1L, NOW_KST);
        Meeting second = meeting(2L, NOW_KST);
        when(meetings.findWaitingForPrewarm(NOW_KST, NOW_KST.plusMinutes(1)))
                .thenReturn(List.of(first, second));
        when(aiClient.requestPrewarm(any()))
                .thenReturn(HttpStatusCode.valueOf(httpStatus), HttpStatusCode.valueOf(202));

        // when
        scheduler.prewarmUpcomingMeetings();

        // then
        verify(aiClient, times(2)).requestPrewarm(any());
        assertThat(meters.counter("ai.stt.prewarm.accepted").count()).isEqualTo(1);
        String reason = switch (httpStatus) {
            case 401 -> "unauthorized";
            case 403 -> "forbidden";
            case 422 -> "invalid_request";
            default -> "server";
        };
        assertThat(meters.counter("ai.stt.prewarm.failures", "reason", reason).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("한 회의의 read timeout 후에도 다른 회의를 처리하고 다음 주기에 재시도한다")
    void retriesNaturallyAfterTimeout() {
        // 테스트 목적:
        // HTTP timeout을 현재 주기에서 즉시 retry하지 않고 다음 주기에만 다시 전송하는지 검증한다.
        // given
        Meeting first = meeting(1L, NOW_KST);
        Meeting second = meeting(2L, NOW_KST);
        when(meetings.findWaitingForPrewarm(NOW_KST, NOW_KST.plusMinutes(1)))
                .thenReturn(List.of(first, second), List.of(first));
        when(aiClient.requestPrewarm(any()))
                .thenThrow(new ResourceAccessException("read timeout", new SocketTimeoutException("Read timed out")))
                .thenReturn(HttpStatusCode.valueOf(202));

        // when
        scheduler.prewarmUpcomingMeetings();
        assertThat(meters.counter("ai.stt.prewarm.timeouts").count()).isEqualTo(1);
        scheduler.prewarmUpcomingMeetings();

        // then
        verify(aiClient, times(3)).requestPrewarm(any());
        assertThat(meters.counter("ai.stt.prewarm.failures", "reason", "timeout").count()).isEqualTo(1);
        assertThat(meters.counter("ai.stt.prewarm.accepted").count()).isEqualTo(2);
    }

    @Test
    @DisplayName("AI 연결 실패 후에도 다음 회의를 처리한다")
    void isolatesConnectionFailure() {
        // 테스트 목적:
        // AI 연결 오류는 transport 실패로 관측하고 뒤따르는 회의 요청은 계속하는지 검증한다.
        // given
        Meeting first = meeting(1L, NOW_KST);
        Meeting second = meeting(2L, NOW_KST);
        when(meetings.findWaitingForPrewarm(NOW_KST, NOW_KST.plusMinutes(1)))
                .thenReturn(List.of(first, second));
        when(aiClient.requestPrewarm(any()))
                .thenThrow(new ResourceAccessException("connection refused", new ConnectException("refused")))
                .thenReturn(HttpStatusCode.valueOf(202));

        // when
        scheduler.prewarmUpcomingMeetings();

        // then
        verify(aiClient, times(2)).requestPrewarm(any());
        assertThat(meters.counter("ai.stt.prewarm.failures", "reason", "transport").count()).isEqualTo(1);
        assertThat(meters.counter("ai.stt.prewarm.accepted").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("조회 실패가 한 주기에 발생해도 다음 주기에 다시 조회할 수 있다")
    void nextCycleRunsAfterRepositoryFailure() {
        // 테스트 목적:
        // DB 조회 예외가 일시적으로 발생한 뒤에도 다음 스케줄 실행에 잔존 상태 없이 다시 처리되는지 검증한다.
        // given
        Meeting target = meeting(1L, NOW_KST);
        when(meetings.findWaitingForPrewarm(NOW_KST, NOW_KST.plusMinutes(1)))
                .thenThrow(new IllegalStateException("DB unavailable"))
                .thenReturn(List.of(target));
        when(aiClient.requestPrewarm(any())).thenReturn(HttpStatusCode.valueOf(202));

        // when
        assertThatThrownBy(scheduler::prewarmUpcomingMeetings).isInstanceOf(IllegalStateException.class);
        scheduler.prewarmUpcomingMeetings();

        // then
        assertThat(meters.counter("ai.stt.prewarm.scheduler.runs").count()).isEqualTo(2);
        assertThat(meters.counter("ai.stt.prewarm.accepted").count()).isEqualTo(1);
    }

    private Meeting meeting(Long id, LocalDateTime scheduledAt) {
        Meeting meeting = mock(Meeting.class);
        when(meeting.getId()).thenReturn(id);
        when(meeting.getScheduledAt()).thenReturn(scheduledAt);
        return meeting;
    }
}
