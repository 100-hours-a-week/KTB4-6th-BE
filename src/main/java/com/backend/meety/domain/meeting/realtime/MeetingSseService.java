package com.backend.meety.domain.meeting.realtime;

import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.ParticipationStatus;
import com.backend.meety.domain.meeting.exception.MeetingErrorCode;
import com.backend.meety.domain.meeting.exception.MeetingException;
import com.backend.meety.domain.meeting.repository.MeetingParticipantRepository;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import jakarta.servlet.DispatcherType;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingSseService {

    private static final String CONNECTED_EVENT_NAME = "CONNECTED";
    private static final long SLOW_LOG_THRESHOLD_MS = 1_000L;

    private final MeetingRepository meetingRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final MeetingParticipantRepository meetingParticipantRepository;
    private final MeetingSseRegistry registry;
    private final MeetingSseEmitterFactory emitterFactory;
    private DataSource dataSource;

    @Autowired(required = false)
    void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Transactional(readOnly = true)
    public SseEmitter connect(Long userId, Long meetingId) {
        long totalStartedAt = System.nanoTime();
        logConnectionDiag("SERVICE_ENTER", meetingId, userId, null);
        log.info("[SSE_CONNECT] start meetingId={}, userId={}, thread={}",
                meetingId, userId, Thread.currentThread().getName());
        logHikari("SSE_CONNECT start");

        long segmentStartedAt = System.nanoTime();
        log.info("[SSE_CONNECT] meeting lookup start meetingId={}, userId={}", meetingId, userId);
        Meeting meeting = meetingRepository.findByIdAndDeletedAtIsNull(meetingId)
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.MEETING_NOT_FOUND));
        logElapsed("[SSE_CONNECT] meeting lookup completed meetingId={}, userId={}, teamId={}, elapsedMs={}",
                elapsedMs(segmentStartedAt), meetingId, userId, meeting.getTeam().getId());

        segmentStartedAt = System.nanoTime();
        log.info("[SSE_CONNECT] membership validation start meetingId={}, userId={}, teamId={}",
                meetingId, userId, meeting.getTeam().getId());
        TeamMember teamMember = teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(
                        meeting.getTeam().getId(), userId, MembershipStatus.ACTIVE)
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.MEETING_ACCESS_DENIED));
        logElapsed("[SSE_CONNECT] membership validation completed meetingId={}, userId={}, teamId={}, "
                        + "teamMemberId={}, elapsedMs={}",
                elapsedMs(segmentStartedAt), meetingId, userId, meeting.getTeam().getId(), teamMember.getId());

        segmentStartedAt = System.nanoTime();
        log.info("[SSE_CONNECT] participant validation start meetingId={}, userId={}, teamMemberId={}",
                meetingId, userId, teamMember.getId());
        if (!meetingParticipantRepository.existsByMeetingIdAndTeamMemberIdAndParticipationStatusAndDeletedAtIsNull(
                meetingId, teamMember.getId(), ParticipationStatus.JOINED)) {
            throw new MeetingException(MeetingErrorCode.MEETING_PARTICIPANT_REQUIRED);
        }
        logElapsed("[SSE_CONNECT] participant validation completed meetingId={}, userId={}, teamMemberId={}, "
                        + "elapsedMs={}",
                elapsedMs(segmentStartedAt), meetingId, userId, teamMember.getId());
        logConnectionDiag("DB_VALIDATION_COMPLETED", meetingId, userId, null);

        segmentStartedAt = System.nanoTime();
        SseEmitter emitter = emitterFactory.create();
        logElapsed("[SSE_CONNECT] emitter created meetingId={}, userId={}, elapsedMs={}",
                elapsedMs(segmentStartedAt), meetingId, userId);

        segmentStartedAt = System.nanoTime();
        log.info("[SSE_CONNECT] registry register start meetingId={}, userId={}", meetingId, userId);
        registerLifecycleCallbacks(meetingId, userId, emitter);
        registry.register(meetingId, userId, emitter)
                .ifPresent(previous -> {
                    log.info("[SSE] 기존 연결을 교체합니다. meetingId={}, userId={}", meetingId, userId);
                    previous.complete();
                });
        log.info("[SSE] 연결 등록 완료. meetingId={}, userId={}, meetingEmitters={}, totalEmitters={}",
                meetingId, userId, registry.count(meetingId), registry.countAll());
        logElapsed("[SSE_CONNECT] registry register completed meetingId={}, userId={}, elapsedMs={}",
                elapsedMs(segmentStartedAt), meetingId, userId);
        logConnectionDiag("EMITTER_REGISTERED", meetingId, userId, null);

        try {
            segmentStartedAt = System.nanoTime();
            log.info("[SSE_CONNECT] connected event send start meetingId={}, userId={}", meetingId, userId);
            sendConnectedEvent(meetingId, emitter);
            logElapsed("[SSE_CONNECT] connected event send end meetingId={}, userId={}, elapsedMs={}",
                    elapsedMs(segmentStartedAt), meetingId, userId);
            logConnectionDiag("CONNECTED_EVENT_SENT", meetingId, userId, null);
        } catch (IOException e) {
            registry.remove(meetingId, userId, emitter);
            emitter.completeWithError(e);
            log.warn("SSE 최초 연결 이벤트 전송에 실패했습니다. meetingId={}, userId={}", meetingId, userId, e);
            throw new IllegalStateException("SSE initial event send failed", e);
        }
        logHikari("SSE_CONNECT end");
        logElapsed("[SSE_CONNECT] end meetingId={}, userId={}, totalElapsedMs={}",
                elapsedMs(totalStartedAt), meetingId, userId);
        logConnectionDiag("SERVICE_RETURN_BEFORE", meetingId, userId, null);
        return emitter;
    }

    private void registerLifecycleCallbacks(Long meetingId, Long userId, SseEmitter emitter) {
        emitter.onCompletion(() -> removeAndLog(meetingId, userId, emitter, "completion", null));
        emitter.onTimeout(() -> removeAndLog(meetingId, userId, emitter, "timeout", null));
        emitter.onError(throwable -> removeAndLog(meetingId, userId, emitter, "error", throwable));
    }

    private void removeAndLog(Long meetingId, Long userId, SseEmitter emitter, String reason, Throwable throwable) {
        registry.remove(meetingId, userId, emitter);
        logConnectionDiag(lifecyclePhase(reason), meetingId, userId, reason);
        if (throwable == null) {
            log.info("[SSE] 연결이 종료되었습니다. meetingId={}, userId={}, reason={}, "
                            + "meetingEmitters={}, totalEmitters={}",
                    meetingId, userId, reason, registry.count(meetingId), registry.countAll());
            return;
        }
        log.warn("[SSE] 연결이 오류로 종료되었습니다. meetingId={}, userId={}, reason={}, "
                        + "meetingEmitters={}, totalEmitters={}, cause={}",
                meetingId, userId, reason, registry.count(meetingId), registry.countAll(),
                throwable.getClass().getSimpleName());
    }

    private String lifecyclePhase(String reason) {
        return switch (reason) {
            case "completion" -> "ON_COMPLETION";
            case "timeout" -> "ON_TIMEOUT";
            case "error" -> "ON_ERROR";
            default -> "ON_CLOSE";
        };
    }

    private void sendConnectedEvent(Long meetingId, SseEmitter emitter) throws IOException {
        emitter.send(SseEmitter.event()
                .name(CONNECTED_EVENT_NAME)
                .data(MeetingSseConnectedEvent.connected(meetingId)));
    }

    private long elapsedMs(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    private void logElapsed(String message, long elapsedMs, Object... args) {
        Object[] values = Arrays.copyOf(args, args.length + 1);
        values[args.length] = elapsedMs;
        if (elapsedMs >= SLOW_LOG_THRESHOLD_MS) {
            log.warn(message, values);
            return;
        }
        log.info(message, values);
    }

    private void logHikari(String context) {
        if (!(dataSource instanceof HikariDataSource hikariDataSource)) {
            return;
        }
        HikariPoolMXBean pool = hikariDataSource.getHikariPoolMXBean();
        if (pool == null) {
            return;
        }
        log.info("[HIKARI] context={} active={} idle={} total={} pending={}",
                context,
                pool.getActiveConnections(),
                pool.getIdleConnections(),
                pool.getTotalConnections(),
                pool.getThreadsAwaitingConnection());
    }

    private void logConnectionDiag(String phase, Long meetingId, Long userId, String reason) {
        HikariPoolMXBean pool = hikariPool();
        log.info("[SSE_CONNECTION_DIAG] phase={} meetingId={} userId={} active={} idle={} total={} pending={} "
                        + "txActive={} syncActive={} thread={} dispatcherType={} reason={}",
                phase,
                meetingId,
                userId,
                activeConnections(pool),
                idleConnections(pool),
                totalConnections(pool),
                pendingThreads(pool),
                TransactionSynchronizationManager.isActualTransactionActive(),
                TransactionSynchronizationManager.isSynchronizationActive(),
                Thread.currentThread().getName(),
                dispatcherType(),
                reason);
    }

    private HikariPoolMXBean hikariPool() {
        if (!(dataSource instanceof HikariDataSource hikariDataSource)) {
            return null;
        }
        return hikariDataSource.getHikariPoolMXBean();
    }

    private int activeConnections(HikariPoolMXBean pool) {
        return pool == null ? -1 : pool.getActiveConnections();
    }

    private int idleConnections(HikariPoolMXBean pool) {
        return pool == null ? -1 : pool.getIdleConnections();
    }

    private int totalConnections(HikariPoolMXBean pool) {
        return pool == null ? -1 : pool.getTotalConnections();
    }

    private int pendingThreads(HikariPoolMXBean pool) {
        return pool == null ? -1 : pool.getThreadsAwaitingConnection();
    }

    private DispatcherType dispatcherType() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletRequestAttributes) {
            return servletRequestAttributes.getRequest().getDispatcherType();
        }
        return null;
    }
}
