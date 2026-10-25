package com.backend.meety.domain.recording.service;

import com.backend.meety.domain.ai.event.AiFatalFailureEvent;
import com.backend.meety.domain.ai.event.AiRecoveryExhaustedEvent;
import com.backend.meety.domain.ai.realtime.AiLiveMeetingConnectionRegistry;
import com.backend.meety.domain.ai.realtime.AiRecoveryRegistry;
import com.backend.meety.domain.credit.entity.CreditLedger;
import com.backend.meety.domain.credit.entity.CreditSourceType;
import com.backend.meety.domain.credit.entity.CreditTransactionType;
import com.backend.meety.domain.credit.exception.CreditErrorCode;
import com.backend.meety.domain.credit.exception.CreditException;
import com.backend.meety.domain.credit.repository.CreditLedgerRepository;
import com.backend.meety.domain.credit.repository.TeamCreditRepository;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingStatus;
import com.backend.meety.domain.meeting.event.MeetingCompletedEvent;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.recording.entity.RecordingSessionStatus;
import com.backend.meety.domain.recording.event.RecordingFailedEvent;
import com.backend.meety.domain.recording.owner.RecordingOwnerRepository;
import com.backend.meety.domain.recording.realtime.RealtimeFailureReason;
import com.backend.meety.domain.recording.repository.RecordingSessionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RealtimeFatalFailureService {
    private final RecordingSessionRepository recordings;
    private final MeetingRepository meetings;
    private final RecordingOwnerRepository owners;
    private final AiLiveMeetingConnectionRegistry connections;
    private final AiRecoveryRegistry recoveries;
    private final TeamCreditRepository credits;
    private final CreditLedgerRepository ledgers;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final ApplicationEventPublisher events;

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onFatal(AiFatalFailureEvent event) {
        fail(event, null);
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRecoveryExhausted(AiRecoveryExhaustedEvent event) {
        fail(new AiFatalFailureEvent(event.recordingSessionId(), event.owner(), event.sessionStartRequestId(),
                RealtimeFailureReason.fromRecovery(event.reason())), event.recoveryGeneration());
    }

    private void fail(AiFatalFailureEvent event, String recoveryGeneration) {
        Long meetingId = recordings.findMeetingIdByIdAndDeletedAtIsNull(event.recordingSessionId()).orElse(null);
        if (meetingId == null) { ignored(event); return; }
        // Match the existing STOP lock order so neither path can overwrite the other's terminal state.
        var meeting = meetings.findByIdForUpdateAndDeletedAtIsNull(meetingId).orElse(null);
        if (meeting == null || meeting.getStatus() == MeetingStatus.COMPLETED) { ignored(event); return; }
        var session = recordings.findByIdForUpdateAndDeletedAtIsNull(event.recordingSessionId()).orElse(null);
        if (session == null || session.getStatus() == RecordingSessionStatus.COMPLETED
                || session.getStatus() == RecordingSessionStatus.FAILED || !current(event, recoveryGeneration)) {
            ignored(event); return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        session.fail(now);
        meeting.complete(now);
        restoreRecordingCredit(meeting, session.getId());
        events.publishEvent(new RecordingFailedEvent(meetingId, event.recordingSessionId(), event.owner(), event.reason()));
        events.publishEvent(new MeetingCompletedEvent(meetingId));
        log.warn("실시간 전사 Fatal 확정 요청. meetingId={}, recordingSessionId={}, reason={}",
                meetingId, event.recordingSessionId(), event.reason());
    }

    private void restoreRecordingCredit(Meeting meeting, Long sessionId) {
        String useKey = CreditLedger.keyOf(CreditTransactionType.USE, CreditSourceType.RECORDING, sessionId);
        var charged = ledgers.findByIdempotencyKeyAndDeletedAtIsNull(useKey).orElse(null);
        if (charged == null) {
            log.warn("녹음 차감 원장이 없어 Fatal 보상을 생략합니다. recordingSessionId={}", sessionId);
            return;
        }
        if (!meeting.getTeam().getId().equals(charged.getTeam().getId()) || charged.getAmount() >= 0) {
            throw new CreditException(CreditErrorCode.CREDIT_LEDGER_LOOKUP_FAILED);
        }
        var credit = credits.findByTeamIdForUpdate(meeting.getTeam().getId())
                .orElseThrow(() -> new CreditException(CreditErrorCode.TEAM_CREDIT_NOT_FOUND));
        String restoreKey = CreditLedger.keyOf(CreditTransactionType.RESTORE, CreditSourceType.RECORDING, sessionId);
        if (ledgers.existsByIdempotencyKey(restoreKey)) { return; }
        long amount = Math.negateExact(charged.getAmount());
        credit.restore(amount);
        ledgers.save(CreditLedger.restoreForRecording(meeting.getTeam(), sessionId, amount, credit.getBalance()));
    }

    private boolean current(AiFatalFailureEvent event, String generation) {
        if (event.owner() == null || !owners.find(event.recordingSessionId()).filter(event.owner()::equals).isPresent()) {
            return false;
        }
        if (generation != null && recoveries.hasDifferentGeneration(event.recordingSessionId(), generation)) { return false; }
        var connection = connections.find(event.recordingSessionId()).orElse(null);
        if (connection == null) { return generation != null || event.sessionStartRequestId() == null; }
        if (!event.owner().equals(connection.owner()) || connection.isStopRequested()) { return false; }
        if (event.sessionStartRequestId() == null) { return true; }
        return event.sessionStartRequestId().equals(connection.sessionStartRequestId())
                && (generation != null || (!connection.isClosed() && !connection.isRecoveryFenced()));
    }

    private void ignored(AiFatalFailureEvent event) {
        metrics.counter("recording.fatal.ignored").increment();
        log.debug("종료됐거나 현재 실행이 아닌 Fatal 무시. recordingSessionId={}, reason={}",
                event.recordingSessionId(), event.reason());
    }
}
