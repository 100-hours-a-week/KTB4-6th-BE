package com.backend.meety.domain.notification.event;

import com.backend.meety.domain.credit.event.CreditEarnedEvent;
import com.backend.meety.domain.credit.event.CreditEarnedReason;
import com.backend.meety.domain.meeting.event.SummaryReadyEvent;
import com.backend.meety.domain.notification.entity.NotificationReferenceType;
import com.backend.meety.domain.notification.entity.NotificationType;
import com.backend.meety.domain.notification.service.NotificationService;
import com.backend.meety.domain.recording.entity.RecordingSession;
import com.backend.meety.domain.recording.event.RecordingStartedEvent;
import com.backend.meety.domain.recording.repository.RecordingSessionRepository;
import com.backend.meety.domain.team.event.TeamMemberJoinedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private final NotificationService notificationService;
    private final RecordingSessionRepository recordingSessionRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void createMeetingStartedNotification(RecordingStartedEvent event) {
        try {
            RecordingSession session = recordingSessionRepository
                    .findByIdWithMeetingTeamAndStarter(event.recordingSessionId())
                    .orElseThrow(() -> new IllegalStateException(
                            "녹음 시작 알림 대상 녹음 세션이 없습니다. recordingSessionId="
                                    + event.recordingSessionId()));
            notificationService.notifyActiveTeamMembers(
                    session.getMeeting().getTeam().getId(),
                    NotificationType.MEETING_STARTED,
                    meetingStartedKey(event.recordingSessionId()),
                    NotificationReferenceType.MEETING,
                    event.meetingId(),
                    session.getStartedByTeamMember().getDisplayName() + " 님이 회의를 시작했습니다"
            );
        } catch (RuntimeException e) {
            log.error("회의 시작 알림 생성에 실패했습니다. meetingId={}, recordingSessionId={}",
                    event.meetingId(), event.recordingSessionId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void createSummaryReadyNotification(SummaryReadyEvent event) {
        try {
            notificationService.notifyActiveTeamMembers(
                    event.teamId(),
                    NotificationType.SUMMARY_READY,
                    summaryReadyKey(event.aiRequestId()),
                    NotificationReferenceType.MEETING,
                    event.meetingId(),
                    event.meetingTitle() + " 회의 요약이 새로 생성되었습니다"
            );
        } catch (RuntimeException e) {
            log.error("요약 완료 알림 생성에 실패했습니다. meetingId={}, aiRequestId={}",
                    event.meetingId(), event.aiRequestId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void createCreditEarnedNotification(CreditEarnedEvent event) {
        try {
            notificationService.notifyActiveTeamMembers(
                    event.teamId(),
                    NotificationType.CREDIT_EARNED,
                    creditEarnedKey(event.creditLedgerIdempotencyKey()),
                    NotificationReferenceType.CREDIT,
                    null,
                    creditEarnedBody(event.earnedAmount(), event.reason())
            );
        } catch (RuntimeException e) {
            log.error("크레딧 적립 알림 생성에 실패했습니다. teamId={}, creditLedgerIdempotencyKey={}",
                    event.teamId(), event.creditLedgerIdempotencyKey(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void createMemberJoinedNotification(TeamMemberJoinedEvent event) {
        try {
            notificationService.notifyMemberJoined(
                    event.teamId(),
                    event.joinedUserId(),
                    event.joinedDisplayName(),
                    event.joinType(),
                    memberJoinedKey(event.eventId())
            );
        } catch (RuntimeException e) {
            log.error("팀 가입 알림 생성에 실패했습니다. teamId={}, joinedTeamMemberId={}, eventId={}",
                    event.teamId(), event.joinedTeamMemberId(), event.eventId(), e);
        }
    }

    // TODO: 리포트 생성 완료 흐름이 구현되면 REPORT_READY 알림을 연결한다.

    static String meetingStartedKey(Long recordingSessionId) {
        return "MEETING_STARTED:" + recordingSessionId;
    }

    static String summaryReadyKey(Long aiRequestId) {
        return "SUMMARY_READY:" + aiRequestId;
    }

    static String creditEarnedKey(String creditLedgerIdempotencyKey) {
        return "CREDIT_EARNED:" + creditLedgerIdempotencyKey;
    }

    static String memberJoinedKey(String eventId) {
        return "MEMBER_JOINED:" + eventId;
    }

    static String creditEarnedBody(Long earnedAmount, CreditEarnedReason reason) {
        return switch (reason) {
            case TEAM_CREATE -> "팀 생성으로 " + earnedAmount + " 크레딧이 지급되었습니다";
            case SCHEDULE -> earnedAmount + " 크레딧이 충전되었습니다";
        };
    }
}
