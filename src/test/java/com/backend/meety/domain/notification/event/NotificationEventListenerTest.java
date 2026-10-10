package com.backend.meety.domain.notification.event;

import static com.backend.meety.domain.recording.RecordingFixtures.NOW;
import static com.backend.meety.domain.recording.RecordingFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.credit.event.CreditEarnedEvent;
import com.backend.meety.domain.credit.event.CreditEarnedReason;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.event.SummaryReadyEvent;
import com.backend.meety.domain.notification.entity.NotificationReferenceType;
import com.backend.meety.domain.notification.entity.NotificationType;
import com.backend.meety.domain.notification.service.NotificationService;
import com.backend.meety.domain.recording.entity.RecordingSession;
import com.backend.meety.domain.recording.event.RecordingStartedEvent;
import com.backend.meety.domain.recording.repository.RecordingSessionRepository;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.event.TeamMemberJoinedEvent;
import com.backend.meety.domain.team.event.TeamMemberJoinType;
import com.backend.meety.domain.user.entity.User;
import com.backend.meety.global.config.AsyncConfig;
import java.lang.reflect.Method;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

class NotificationEventListenerTest {

    private final NotificationService notificationService = mock(NotificationService.class);
    private final RecordingSessionRepository recordingSessionRepository = mock(RecordingSessionRepository.class);
    private final NotificationEventListener listener =
            new NotificationEventListener(notificationService, recordingSessionRepository);

    @Test
    @DisplayName("녹음 시작 이벤트는 실제 녹음 시작자 이름으로 회의 시작 알림을 생성한다")
    void recordingStartedCreatesMeetingStartedNotification() {
        // 테스트 목적:
        // 녹음 시작 알림 본문이 회의 생성자가 아니라
        // RecordingSession.startedByTeamMember의 displayName을 기준으로 생성되는지 검증한다.

        // given
        Team team = withId(Team.create("미티팀"), 2L);
        TeamMember creator = member(team, 1L, 10L, "생성자");
        TeamMember starter = member(team, 2L, 11L, "시작자");
        Meeting meeting = withId(Meeting.create(team, creator, "회의", "목적", null, NOW, 30), 100L);
        RecordingSession session = withId(RecordingSession.start(meeting, starter, NOW), 700L);
        when(recordingSessionRepository.findByIdWithMeetingTeamAndStarter(700L))
                .thenReturn(Optional.of(session));

        // when
        listener.createMeetingStartedNotification(new RecordingStartedEvent(100L, 700L));

        // then
        verify(notificationService).notifyActiveTeamMembers(
                2L,
                NotificationType.MEETING_STARTED,
                "MEETING_STARTED:700",
                NotificationReferenceType.MEETING,
                100L,
                "시작자 님이 회의를 시작했습니다"
        );
    }

    @Test
    @DisplayName("요약 완료 이벤트는 SUMMARY_READY 알림 생성에 위임한다")
    void summaryReadyCreatesNotification() {
        // 테스트 목적:
        // 수동 요약 재생성 완료 이벤트가 발생하면
        // 회의 참조를 가진 SUMMARY_READY 알림 생성으로 연결되는지 검증한다.

        // given
        SummaryReadyEvent event = new SummaryReadyEvent(2L, 100L, 900L, "회의");

        // when
        listener.createSummaryReadyNotification(event);

        // then
        verify(notificationService).notifyActiveTeamMembers(
                2L,
                NotificationType.SUMMARY_READY,
                "SUMMARY_READY:900",
                NotificationReferenceType.MEETING,
                100L,
                "회의 회의 요약이 새로 생성되었습니다"
        );
    }

    @Test
    @DisplayName("크레딧 적립 이벤트는 CREDIT_EARNED 알림 생성에 위임한다")
    void creditEarnedCreatesNotification() {
        // 테스트 목적:
        // 실제 주간 크레딧 적립 이벤트가 발생하면
        // CREDIT 참조를 가진 CREDIT_EARNED 알림 생성으로 연결되는지 검증한다.

        // given
        CreditEarnedEvent event = new CreditEarnedEvent(
                2L, "EARN:SCHEDULE:2:2026-W40", 10L, CreditEarnedReason.SCHEDULE);

        // when
        listener.createCreditEarnedNotification(event);

        // then
        verify(notificationService).notifyActiveTeamMembers(
                2L,
                NotificationType.CREDIT_EARNED,
                "CREDIT_EARNED:EARN:SCHEDULE:2:2026-W40",
                NotificationReferenceType.CREDIT,
                null,
                "10 크레딧이 충전되었습니다"
        );
    }

    @Test
    @DisplayName("팀 생성 크레딧 적립 이벤트는 초기 지급 문구로 알림 생성에 위임한다")
    void teamCreateCreditEarnedCreatesInitialGrantNotification() {
        // 테스트 목적:
        // 팀 생성 초기 크레딧 지급 이벤트가 발생하면
        // CREDIT 참조와 초기 지급 문구를 가진 CREDIT_EARNED 알림 생성으로 연결되는지 검증한다.

        // given
        CreditEarnedEvent event = new CreditEarnedEvent(
                2L, "EARN:TEAM_CREATE:2", 100L, CreditEarnedReason.TEAM_CREATE);

        // when
        listener.createCreditEarnedNotification(event);

        // then
        verify(notificationService).notifyActiveTeamMembers(
                2L,
                NotificationType.CREDIT_EARNED,
                "CREDIT_EARNED:EARN:TEAM_CREATE:2",
                NotificationReferenceType.CREDIT,
                null,
                "팀 생성으로 100 크레딧이 지급되었습니다"
        );
    }

    @Test
    @DisplayName("팀원 가입 이벤트는 MEMBER_JOINED 알림 생성에 위임한다")
    void memberJoinedCreatesNotification() {
        // 테스트 목적:
        // 팀 가입 또는 재가입이 성공한 뒤 발생한 이벤트가
        // MEMBER_JOINED 알림 생성으로 연결되는지 검증한다.

        // given
        TeamMemberJoinedEvent event = new TeamMemberJoinedEvent(
                "join-event-1", 2L, 31L, 1L, "hoon", TeamMemberJoinType.NEW);

        // when
        listener.createMemberJoinedNotification(event);

        // then
        verify(notificationService).notifyMemberJoined(
                2L, 1L, "hoon", TeamMemberJoinType.NEW, "MEMBER_JOINED:join-event-1");
    }

    @Test
    @DisplayName("같은 팀원 가입 이벤트 재처리는 같은 MEMBER_JOINED 멱등키를 사용한다")
    void memberJoinedReprocessingUsesSameIdempotencyKey() {
        // 테스트 목적:
        // 동일한 팀원 가입 이벤트 객체가 재처리되는 경우
        // 중복 알림 방지를 위해 같은 idempotencyKey로 알림 생성을 요청하는지 검증한다.

        // given
        TeamMemberJoinedEvent event = new TeamMemberJoinedEvent(
                "join-event-1", 2L, 31L, 1L, "hoon", TeamMemberJoinType.REJOIN);

        // when
        listener.createMemberJoinedNotification(event);
        listener.createMemberJoinedNotification(event);

        // then
        verify(notificationService, times(2))
                .notifyMemberJoined(2L, 1L, "hoon", TeamMemberJoinType.REJOIN, "MEMBER_JOINED:join-event-1");
    }

    @Test
    @DisplayName("알림 생성 실패는 이벤트 리스너 밖으로 전파하지 않는다")
    void notificationFailureDoesNotPropagate() {
        // 테스트 목적:
        // AFTER_COMMIT 알림 생성 중 예외가 발생해도
        // 이미 커밋된 원본 비즈니스 작업에 영향을 주지 않도록 예외를 삼키는지 검증한다.

        // given
        SummaryReadyEvent event = new SummaryReadyEvent(2L, 100L, 900L, "회의");
        doThrow(new RuntimeException("notification failure"))
                .when(notificationService).notifyActiveTeamMembers(
                        2L,
                        NotificationType.SUMMARY_READY,
                        "SUMMARY_READY:900",
                        NotificationReferenceType.MEETING,
                        100L,
                        "회의 회의 요약이 새로 생성되었습니다"
                );

        // when, then
        assertThatCode(() -> listener.createSummaryReadyNotification(event)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("알림 이벤트 리스너는 AFTER_COMMIT에 실행된다")
    void notificationListenersRunAfterCommit() throws NoSuchMethodException {
        // 테스트 목적:
        // 원본 비즈니스 작업 커밋 이후 알림이 생성되도록
        // 모든 알림 리스너 메서드가 AFTER_COMMIT 트랜잭션 이벤트 리스너인지 검증한다.

        // given
        Method meetingStarted = NotificationEventListener.class
                .getMethod("createMeetingStartedNotification", RecordingStartedEvent.class);
        Method summaryReady = NotificationEventListener.class
                .getMethod("createSummaryReadyNotification", SummaryReadyEvent.class);
        Method creditEarned = NotificationEventListener.class
                .getMethod("createCreditEarnedNotification", CreditEarnedEvent.class);
        Method memberJoined = NotificationEventListener.class
                .getMethod("createMemberJoinedNotification", TeamMemberJoinedEvent.class);

        // when, then
        assertThat(meetingStarted.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(summaryReady.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(creditEarned.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(memberJoined.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    @DisplayName("알림 이벤트 리스너는 Notification 전용 Executor에서 비동기로 실행된다")
    void notificationListenersUseDedicatedExecutor() throws NoSuchMethodException {
        // 테스트 목적:
        // 알림 생성 후속 작업이 기본 applicationTaskExecutor가 아니라
        // notificationTaskExecutor로 격리되어 실행되는지 검증한다.

        // given
        Method meetingStarted = NotificationEventListener.class
                .getMethod("createMeetingStartedNotification", RecordingStartedEvent.class);
        Method summaryReady = NotificationEventListener.class
                .getMethod("createSummaryReadyNotification", SummaryReadyEvent.class);
        Method creditEarned = NotificationEventListener.class
                .getMethod("createCreditEarnedNotification", CreditEarnedEvent.class);
        Method memberJoined = NotificationEventListener.class
                .getMethod("createMemberJoinedNotification", TeamMemberJoinedEvent.class);

        // when, then
        assertThat(meetingStarted.getAnnotation(Async.class).value())
                .isEqualTo(AsyncConfig.NOTIFICATION_TASK_EXECUTOR_BEAN_NAME);
        assertThat(summaryReady.getAnnotation(Async.class).value())
                .isEqualTo(AsyncConfig.NOTIFICATION_TASK_EXECUTOR_BEAN_NAME);
        assertThat(creditEarned.getAnnotation(Async.class).value())
                .isEqualTo(AsyncConfig.NOTIFICATION_TASK_EXECUTOR_BEAN_NAME);
        assertThat(memberJoined.getAnnotation(Async.class).value())
                .isEqualTo(AsyncConfig.NOTIFICATION_TASK_EXECUTOR_BEAN_NAME);
    }

    private TeamMember member(Team team, Long userId, Long memberId, String displayName) {
        User user = withId(User.create(), userId);
        return withId(TeamMember.createMember(user, team, displayName), memberId);
    }
}
