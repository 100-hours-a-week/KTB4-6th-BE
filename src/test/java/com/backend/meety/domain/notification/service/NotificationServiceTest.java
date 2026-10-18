package com.backend.meety.domain.notification.service;

import static com.backend.meety.domain.recording.RecordingFixtures.CLOCK;
import static com.backend.meety.domain.recording.RecordingFixtures.NOW;
import static com.backend.meety.domain.recording.RecordingFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

import com.backend.meety.domain.notification.dto.NotificationListResponse;
import com.backend.meety.domain.notification.dto.NotificationReadRequest;
import com.backend.meety.domain.notification.dto.NotificationReadResponse;
import com.backend.meety.domain.notification.dto.NotificationsReadResponse;
import com.backend.meety.domain.notification.entity.Notification;
import com.backend.meety.domain.notification.entity.NotificationReferenceType;
import com.backend.meety.domain.notification.entity.NotificationType;
import com.backend.meety.domain.notification.exception.NotificationErrorCode;
import com.backend.meety.domain.notification.repository.NotificationRepository;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.event.TeamMemberJoinType;
import com.backend.meety.domain.team.exception.TeamErrorCode;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.domain.user.entity.User;
import com.backend.meety.global.exception.BusinessException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

class NotificationServiceTest {

    private final TeamMemberRepository teamMemberRepository = mock(TeamMemberRepository.class);
    private final NotificationWriter notificationWriter = mock(NotificationWriter.class);
    private final NotificationRepository notificationRepository = mock(NotificationRepository.class);
    private final NotificationService service =
            new NotificationService(teamMemberRepository, notificationWriter, notificationRepository, CLOCK);

    @Test
    @DisplayName("활성 팀원 전체에게 같은 이벤트 알림을 생성한다")
    void notifyActiveTeamMembersCreatesNotificationForAllActiveMembers() {
        // 테스트 목적:
        // 알림 이벤트가 발생했을 때 해당 팀의 ACTIVE 팀원 전체를 수신자로 조회하고
        // 각 사용자별 알림 저장을 공통 Writer에 위임하는지 검증한다.

        // given
        Team team = withId(Team.create("미티팀"), 2L);
        TeamMember first = member(team, 1L, 10L, "리더");
        TeamMember second = member(team, 2L, 11L, "팀원");
        when(teamMemberRepository.findAllByTeamIdAndMembershipStatusWithUserAndTeam(
                2L, MembershipStatus.ACTIVE)).thenReturn(List.of(first, second));

        // when
        service.notifyActiveTeamMembers(
                2L,
                NotificationType.MEETING_STARTED,
                "MEETING_STARTED:700",
                NotificationReferenceType.MEETING,
                100L,
                "리더 님이 회의를 시작했습니다"
        );

        // then
        ArgumentCaptor<NotificationWriteCommand> captor = ArgumentCaptor.forClass(NotificationWriteCommand.class);
        verify(notificationWriter, times(2)).create(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(NotificationWriteCommand::userId)
                .containsExactly(1L, 2L);
        assertThat(captor.getAllValues()).allSatisfy(command -> {
            assertThat(command.teamId()).isEqualTo(2L);
            assertThat(command.type()).isEqualTo(NotificationType.MEETING_STARTED);
            assertThat(command.idempotencyKey()).isEqualTo("MEETING_STARTED:700");
            assertThat(command.referenceType()).isEqualTo(NotificationReferenceType.MEETING);
            assertThat(command.referenceId()).isEqualTo(100L);
            assertThat(command.body()).isEqualTo("리더 님이 회의를 시작했습니다");
        });
    }

    @Test
    @DisplayName("동일 이벤트 재처리로 중복 키가 발생하면 멱등하게 무시한다")
    void duplicateNotificationDoesNotPropagate() {
        // 테스트 목적:
        // 같은 사용자에게 같은 idempotencyKey 알림이 이미 생성되어 DB UNIQUE 충돌이 발생해도
        // 이벤트 재처리로 보고 예외가 전파되지 않는지 검증한다.

        // given
        Team team = withId(Team.create("미티팀"), 2L);
        TeamMember member = member(team, 1L, 10L, "리더");
        when(teamMemberRepository.findAllByTeamIdAndMembershipStatusWithUserAndTeam(
                2L, MembershipStatus.ACTIVE)).thenReturn(List.of(member));
        doThrow(new DataIntegrityViolationException("uk_notification_user_id_idempotency_key"))
                .when(notificationWriter).create(any(NotificationWriteCommand.class));

        // when, then
        assertThatCode(() -> service.notifyActiveTeamMembers(
                2L,
                NotificationType.CREDIT_EARNED,
                "CREDIT_EARNED:EARN:SCHEDULE:2:2026-W40",
                NotificationReferenceType.CREDIT,
                null,
                "10 크레딧이 충전되었습니다"
        )).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("일부 수신자의 중복 알림은 다른 수신자의 알림 생성을 막지 않는다")
    void duplicateNotificationSkipsOnlyThatRecipient() {
        // 테스트 목적:
        // ACTIVE 팀원 여러 명에게 알림을 생성하는 중 한 사용자에게만 UNIQUE 충돌이 발생해도
        // 해당 사용자만 건너뛰고 이후 수신자 알림 생성은 계속되는지 검증한다.

        // given
        Team team = withId(Team.create("미티팀"), 2L);
        TeamMember first = member(team, 1L, 10L, "a");
        TeamMember second = member(team, 2L, 11L, "b");
        TeamMember third = member(team, 3L, 12L, "c");
        TeamMember fourth = member(team, 4L, 13L, "d");
        when(teamMemberRepository.findAllByTeamIdAndMembershipStatusWithUserAndTeam(
                2L, MembershipStatus.ACTIVE)).thenReturn(List.of(first, second, third, fourth));
        doAnswer(invocation -> {
            NotificationWriteCommand command = invocation.getArgument(0);
            if (command.userId().equals(3L)) {
                throw new DataIntegrityViolationException("uk_notification_user_id_idempotency_key");
            }
            return null;
        }).when(notificationWriter).create(any(NotificationWriteCommand.class));

        // when
        service.notifyActiveTeamMembers(
                2L,
                NotificationType.MEETING_STARTED,
                "MEETING_STARTED:700",
                NotificationReferenceType.MEETING,
                100L,
                "a 님이 회의를 시작했습니다"
        );

        // then
        ArgumentCaptor<NotificationWriteCommand> captor = ArgumentCaptor.forClass(NotificationWriteCommand.class);
        verify(notificationWriter, times(4)).create(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(NotificationWriteCommand::userId)
                .containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    @DisplayName("팀 가입 알림은 가입자와 기존 팀원의 문구 분기 지점을 가진다")
    void memberJoinedBodyKeepsSeparateBranchForJoinedMember() {
        // 테스트 목적:
        // MEMBER_JOINED 알림에서 가입자 본인과 기존 팀원 문구를 별도 분기할 수 있는 구조를 유지하되
        // 가입자 본인 문구 정책이 확정되기 전까지 새 문구를 만들지 않는지 검증한다.

        // given
        NotificationRecipient joinedMember = new NotificationRecipient(2L, 1L);
        NotificationRecipient existingMember = new NotificationRecipient(2L, 2L);

        // when
        String newJoinedBody = service.memberJoinedBody(joinedMember, 1L, "hoon", TeamMemberJoinType.NEW);
        String newExistingBody = service.memberJoinedBody(existingMember, 1L, "hoon", TeamMemberJoinType.NEW);
        String rejoinedBody = service.memberJoinedBody(joinedMember, 1L, "hoon", TeamMemberJoinType.REJOIN);
        String rejoinedExistingBody = service.memberJoinedBody(existingMember, 1L, "hoon", TeamMemberJoinType.REJOIN);

        // then
        assertThat(newJoinedBody).isEqualTo(service.newJoinedMemberBody("hoon"));
        assertThat(newExistingBody).isEqualTo(service.newExistingMemberJoinedBody("hoon"));
        assertThat(rejoinedBody).isEqualTo(service.rejoinedMemberBody("hoon"));
        assertThat(rejoinedExistingBody).isEqualTo(service.rejoinedExistingMemberJoinedBody("hoon"));
        assertThat(newJoinedBody).isEqualTo("hoon 님, 팀에 오신 걸 환영해요!");
        assertThat(newExistingBody).isEqualTo("hoon 님이 팀에 합류했어요!");
        assertThat(rejoinedBody).isEqualTo("hoon 님, 다시 오신 걸 환영해요!");
        assertThat(rejoinedExistingBody).isEqualTo("hoon 님이 팀에 다시 합류했어요!");
    }

    @Test
    @DisplayName("같은 크레딧 원장 이벤트 재처리는 사용자별 중복 알림을 건너뛴다")
    void creditEarnedReprocessingSkipsDuplicatePerRecipient() {
        // 테스트 목적:
        // 동일한 CreditLedger idempotencyKey 기반 CREDIT_EARNED 이벤트가 재처리되어도
        // 사용자별 UNIQUE 충돌은 중복 알림으로 보고 예외 없이 건너뛰는지 검증한다.

        // given
        Team team = withId(Team.create("미티팀"), 2L);
        TeamMember first = member(team, 1L, 10L, "a");
        TeamMember second = member(team, 2L, 11L, "b");
        when(teamMemberRepository.findAllByTeamIdAndMembershipStatusWithUserAndTeam(
                2L, MembershipStatus.ACTIVE)).thenReturn(List.of(first, second));
        Set<String> createdKeys = new HashSet<>();
        doAnswer(invocation -> {
            NotificationWriteCommand command = invocation.getArgument(0);
            String userEventKey = command.userId() + ":" + command.idempotencyKey();
            if (!createdKeys.add(userEventKey)) {
                throw new DataIntegrityViolationException("uk_notification_user_id_idempotency_key");
            }
            return null;
        }).when(notificationWriter).create(any(NotificationWriteCommand.class));

        // when, then
        assertThatCode(() -> {
            service.notifyActiveTeamMembers(
                    2L,
                    NotificationType.CREDIT_EARNED,
                    "CREDIT_EARNED:EARN:SCHEDULE:2:2026-W40",
                    NotificationReferenceType.CREDIT,
                    null,
                    "10 크레딧이 충전되었습니다"
            );
            service.notifyActiveTeamMembers(
                    2L,
                    NotificationType.CREDIT_EARNED,
                    "CREDIT_EARNED:EARN:SCHEDULE:2:2026-W40",
                    NotificationReferenceType.CREDIT,
                    null,
                    "10 크레딧이 충전되었습니다"
            );
        }).doesNotThrowAnyException();
        ArgumentCaptor<NotificationWriteCommand> captor = ArgumentCaptor.forClass(NotificationWriteCommand.class);
        verify(notificationWriter, times(4)).create(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(command ->
                assertThat(command.idempotencyKey()).isEqualTo("CREDIT_EARNED:EARN:SCHEDULE:2:2026-W40"));
    }

    @Test
    @DisplayName("알림 목록은 현재 ACTIVE 팀의 본인 알림을 최신순으로 반환한다")
    void getNotificationsReturnsCurrentTeamUserNotifications() {
        // 테스트 목적:
        // 로그인 사용자의 현재 ACTIVE 팀을 기준으로 본인 알림 목록을 조회하고
        // Repository가 반환한 최신순 결과를 응답 항목으로 변환하는지 검증한다.

        // given
        givenActiveTeam(1L, 2L);
        Notification first = notification(30L, 2L, 1L, "첫 번째", false);
        Notification second = notification(20L, 2L, 1L, "두 번째", true);
        when(notificationRepository.findPageByUserIdAndTeamId(eq(1L), eq(2L), eq(null), any(Pageable.class)))
                .thenReturn(List.of(first, second));
        when(notificationRepository.countUnreadByUserIdAndTeamId(1L, 2L)).thenReturn(7L);

        // when
        NotificationListResponse response = service.getNotifications(1L, null, null);

        // then
        assertThat(response.notifications())
                .extracting(item -> item.notificationId())
                .containsExactly(30L, 20L);
        assertThat(response.notifications())
                .extracting(item -> item.body())
                .containsExactly("첫 번째", "두 번째");
        assertThat(response.unreadCount()).isEqualTo(7L);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(notificationRepository).findPageByUserIdAndTeamId(eq(1L), eq(2L), eq(null), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(21);
    }

    @Test
    @DisplayName("알림 목록 첫 페이지는 size보다 하나 더 조회해 다음 페이지 여부를 계산한다")
    void getNotificationsFirstPageHasNext() {
        // 테스트 목적:
        // 첫 페이지 조회에서 size + 1개가 조회되면
        // 응답은 size개만 반환하고 마지막 반환 알림 ID를 nextCursor로 제공하는지 검증한다.

        // given
        givenActiveTeam(1L, 2L);
        when(notificationRepository.findPageByUserIdAndTeamId(eq(1L), eq(2L), eq(null), any(Pageable.class)))
                .thenReturn(List.of(
                        notification(5L, 2L, 1L, "5", false),
                        notification(4L, 2L, 1L, "4", false),
                        notification(3L, 2L, 1L, "3", false)
                ));

        // when
        NotificationListResponse response = service.getNotifications(1L, null, 2);

        // then
        assertThat(response.notifications())
                .extracting(item -> item.notificationId())
                .containsExactly(5L, 4L);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isEqualTo(4L);
    }

    @Test
    @DisplayName("cursor가 있으면 해당 ID보다 작은 알림을 다음 페이지로 조회한다")
    void getNotificationsUsesCursorForNextPage() {
        // 테스트 목적:
        // cursor 기반 다음 페이지 요청에서
        // notificationId보다 작은 알림만 조회하도록 cursor 값을 Repository에 전달하는지 검증한다.

        // given
        givenActiveTeam(1L, 2L);
        when(notificationRepository.findPageByUserIdAndTeamId(eq(1L), eq(2L), eq(4L), any(Pageable.class)))
                .thenReturn(List.of(
                        notification(3L, 2L, 1L, "3", false),
                        notification(2L, 2L, 1L, "2", false)
                ));

        // when
        NotificationListResponse response = service.getNotifications(1L, "4", 2);

        // then
        assertThat(response.notifications())
                .extracting(item -> item.notificationId())
                .containsExactly(3L, 2L);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    @DisplayName("마지막 페이지는 nextCursor 없이 hasNext false를 반환한다")
    void getNotificationsLastPage() {
        // 테스트 목적:
        // 조회 결과가 요청 size 이하인 마지막 페이지에서
        // 추가 페이지가 없음을 응답하는지 검증한다.

        // given
        givenActiveTeam(1L, 2L);
        when(notificationRepository.findPageByUserIdAndTeamId(eq(1L), eq(2L), eq(3L), any(Pageable.class)))
                .thenReturn(List.of(notification(2L, 2L, 1L, "2", false)));

        // when
        NotificationListResponse response = service.getNotifications(1L, "3", 2);

        // then
        assertThat(response.notifications()).hasSize(1);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    @DisplayName("조회할 알림이 없으면 빈 목록을 반환한다")
    void getNotificationsEmpty() {
        // 테스트 목적:
        // 현재 ACTIVE 팀에 본인 알림이 없는 경우
        // 오류가 아닌 빈 목록과 unreadCount를 반환하는지 검증한다.

        // given
        givenActiveTeam(1L, 2L);
        when(notificationRepository.findPageByUserIdAndTeamId(eq(1L), eq(2L), eq(null), any(Pageable.class)))
                .thenReturn(List.of());
        when(notificationRepository.countUnreadByUserIdAndTeamId(1L, 2L)).thenReturn(0L);

        // when
        NotificationListResponse response = service.getNotifications(1L, null, null);

        // then
        assertThat(response.notifications()).isEmpty();
        assertThat(response.unreadCount()).isZero();
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    @DisplayName("size 1은 정상 조회한다")
    void getNotificationsAllowsSizeOne() {
        // 테스트 목적:
        // 알림 목록 페이지 크기의 하한인 1이 유효한 요청으로 처리되는지 검증한다.

        // given
        givenActiveTeam(1L, 2L);
        when(notificationRepository.findPageByUserIdAndTeamId(eq(1L), eq(2L), eq(null), any(Pageable.class)))
                .thenReturn(List.of(notification(1L, 2L, 1L, "1", false)));

        // when
        service.getNotifications(1L, null, 1);

        // then
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(notificationRepository).findPageByUserIdAndTeamId(eq(1L), eq(2L), eq(null), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(2);
    }

    @Test
    @DisplayName("size 50은 정상 조회한다")
    void getNotificationsAllowsSizeFifty() {
        // 테스트 목적:
        // 알림 목록 페이지 크기의 상한인 50이 유효한 요청으로 처리되는지 검증한다.

        // given
        givenActiveTeam(1L, 2L);
        when(notificationRepository.findPageByUserIdAndTeamId(eq(1L), eq(2L), eq(null), any(Pageable.class)))
                .thenReturn(List.of());

        // when
        service.getNotifications(1L, null, 50);

        // then
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(notificationRepository).findPageByUserIdAndTeamId(eq(1L), eq(2L), eq(null), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(51);
    }

    @Test
    @DisplayName("size 0은 목록 조회에 실패한다")
    void getNotificationsRejectsSizeZero() {
        // 테스트 목적:
        // 페이지 크기가 최소값보다 작은 경우
        // INVALID_PAGE_SIZE로 거부되는지 검증한다.

        // when, then
        assertThatThrownBy(() -> service.getNotifications(1L, null, 0))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(NotificationErrorCode.INVALID_PAGE_SIZE));
        verify(notificationRepository, never()).findPageByUserIdAndTeamId(anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("size 51은 목록 조회에 실패한다")
    void getNotificationsRejectsSizeFiftyOne() {
        // 테스트 목적:
        // 페이지 크기가 최대값보다 큰 경우
        // INVALID_PAGE_SIZE로 거부되는지 검증한다.

        // when, then
        assertThatThrownBy(() -> service.getNotifications(1L, null, 51))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(NotificationErrorCode.INVALID_PAGE_SIZE));
        verify(notificationRepository, never()).findPageByUserIdAndTeamId(anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("잘못된 cursor는 목록 조회에 실패한다")
    void getNotificationsRejectsInvalidCursor() {
        // 테스트 목적:
        // notificationId로 파싱할 수 없는 cursor 요청이
        // INVALID_CURSOR로 거부되는지 검증한다.

        // when, then
        assertThatThrownBy(() -> service.getNotifications(1L, "not-a-number", 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(NotificationErrorCode.INVALID_CURSOR));
        verify(notificationRepository, never()).findPageByUserIdAndTeamId(anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("unreadCount는 현재 사용자와 팀 기준으로 조회한다")
    void getNotificationsReturnsUnreadCount() {
        // 테스트 목적:
        // 목록 페이지 크기와 관계없이 현재 ACTIVE 팀의 본인 미읽음 알림 수를
        // 별도로 조회해 응답하는지 검증한다.

        // given
        givenActiveTeam(1L, 2L);
        when(notificationRepository.findPageByUserIdAndTeamId(eq(1L), eq(2L), eq(null), any(Pageable.class)))
                .thenReturn(List.of(notification(1L, 2L, 1L, "1", false)));
        when(notificationRepository.countUnreadByUserIdAndTeamId(1L, 2L)).thenReturn(3L);

        // when
        NotificationListResponse response = service.getNotifications(1L, null, 20);

        // then
        assertThat(response.unreadCount()).isEqualTo(3L);
        verify(notificationRepository).countUnreadByUserIdAndTeamId(1L, 2L);
    }

    @Test
    @DisplayName("개별 알림을 읽음 처리한다")
    void markAsRead() {
        // 테스트 목적:
        // 현재 ACTIVE 팀에 속한 본인 알림 하나를 읽음 처리하고
        // 남은 미읽음 수를 응답하는지 검증한다.

        // given
        givenActiveTeam(1L, 2L);
        Notification notification = notification(10L, 2L, 1L, "읽을 알림", false);
        when(notificationRepository.findByIdAndUserIdAndTeamIdAndDeletedAtIsNull(10L, 1L, 2L))
                .thenReturn(Optional.of(notification));
        when(notificationRepository.countUnreadByUserIdAndTeamId(1L, 2L)).thenReturn(4L);

        // when
        NotificationReadResponse response = service.markAsRead(1L, 10L, new NotificationReadRequest(true));

        // then
        assertThat(notification.isRead()).isTrue();
        assertThat(response.notificationId()).isEqualTo(10L);
        assertThat(response.isRead()).isTrue();
        assertThat(response.unreadCount()).isEqualTo(4L);
    }

    @Test
    @DisplayName("이미 읽은 알림 읽음 요청도 성공한다")
    void markAsReadAlreadyRead() {
        // 테스트 목적:
        // 이미 읽음 상태인 본인 알림에 대한 읽음 요청이
        // 멱등하게 성공 응답을 반환하는지 검증한다.

        // given
        givenActiveTeam(1L, 2L);
        Notification notification = notification(10L, 2L, 1L, "이미 읽음", true);
        when(notificationRepository.findByIdAndUserIdAndTeamIdAndDeletedAtIsNull(10L, 1L, 2L))
                .thenReturn(Optional.of(notification));

        // when
        NotificationReadResponse response = service.markAsRead(1L, 10L, new NotificationReadRequest(true));

        // then
        assertThat(notification.isRead()).isTrue();
        assertThat(response.notificationId()).isEqualTo(10L);
        assertThat(response.isRead()).isTrue();
    }

    @Test
    @DisplayName("타 사용자 알림 읽음 요청은 알림 없음으로 거부한다")
    void markAsReadRejectsOtherUserNotification() {
        // 테스트 목적:
        // 요청 사용자의 알림이 아니거나 현재 ACTIVE 팀의 알림이 아닌 경우
        // 알림 존재 여부를 노출하지 않고 NOTIFICATION_NOT_FOUND로 거부하는지 검증한다.

        // given
        givenActiveTeam(1L, 2L);
        when(notificationRepository.findByIdAndUserIdAndTeamIdAndDeletedAtIsNull(10L, 1L, 2L))
                .thenReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> service.markAsRead(1L, 10L, new NotificationReadRequest(true)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
    }

    @Test
    @DisplayName("읽은 알림을 삭제한다")
    void deleteReadNotification() {
        // 테스트 목적:
        // 현재 ACTIVE 팀에 속한 본인의 읽은 알림도
        // hard delete가 아니라 deletedAt 설정으로 삭제되는지 검증한다.

        // given
        givenActiveTeam(1L, 2L);
        Notification notification = notification(10L, 2L, 1L, "읽은 알림", true);
        when(notificationRepository.findByIdAndUserIdAndTeamIdAndDeletedAtIsNull(10L, 1L, 2L))
                .thenReturn(Optional.of(notification));

        // when
        service.delete(1L, 10L);

        // then
        assertThat(notification.isRead()).isTrue();
        assertThat(notification.getDeletedAt()).isEqualTo(NOW);
        verify(notificationRepository, never()).delete(any(Notification.class));
    }

    @Test
    @DisplayName("읽지 않은 알림을 삭제한다")
    void deleteUnreadNotification() {
        // 테스트 목적:
        // 읽음 여부와 삭제 여부가 독립적으로 관리되어
        // 읽지 않은 알림도 삭제할 수 있는지 검증한다.

        // given
        givenActiveTeam(1L, 2L);
        Notification notification = notification(10L, 2L, 1L, "읽지 않은 알림", false);
        when(notificationRepository.findByIdAndUserIdAndTeamIdAndDeletedAtIsNull(10L, 1L, 2L))
                .thenReturn(Optional.of(notification));

        // when
        service.delete(1L, 10L);

        // then
        assertThat(notification.isRead()).isFalse();
        assertThat(notification.getDeletedAt()).isEqualTo(NOW);
        verify(notificationRepository, never()).delete(any(Notification.class));
    }

    @Test
    @DisplayName("다른 사용자의 알림 삭제 요청은 알림 없음으로 거부한다")
    void deleteRejectsOtherUserNotification() {
        // 테스트 목적:
        // 삭제 대상이 현재 사용자의 알림이 아니면
        // NOTIFICATION_NOT_FOUND로 거부되는지 검증한다.

        // given
        givenActiveTeam(1L, 2L);
        when(notificationRepository.findByIdAndUserIdAndTeamIdAndDeletedAtIsNull(10L, 1L, 2L))
                .thenReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> service.delete(1L, 10L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
    }

    @Test
    @DisplayName("다른 팀 알림 삭제 요청은 알림 없음으로 거부한다")
    void deleteRejectsOtherTeamNotification() {
        // 테스트 목적:
        // 삭제 대상이 현재 ACTIVE 팀의 알림이 아니면
        // NOTIFICATION_NOT_FOUND로 거부되는지 검증한다.

        // given
        givenActiveTeam(1L, 2L);
        when(notificationRepository.findByIdAndUserIdAndTeamIdAndDeletedAtIsNull(10L, 1L, 2L))
                .thenReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> service.delete(1L, 10L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
    }

    @Test
    @DisplayName("존재하지 않는 알림 삭제 요청은 알림 없음으로 거부한다")
    void deleteRejectsUnknownNotification() {
        // 테스트 목적:
        // notificationId에 해당하는 삭제 가능 알림이 없으면
        // NOTIFICATION_NOT_FOUND로 거부되는지 검증한다.

        // given
        givenActiveTeam(1L, 2L);
        when(notificationRepository.findByIdAndUserIdAndTeamIdAndDeletedAtIsNull(999L, 1L, 2L))
                .thenReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> service.delete(1L, 999L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
    }

    @Test
    @DisplayName("이미 삭제된 알림 재삭제 요청은 알림 없음으로 거부한다")
    void deleteRejectsAlreadyDeletedNotification() {
        // 테스트 목적:
        // Repository 조회 조건이 deletedAt IS NULL이므로
        // 이미 삭제된 알림 재삭제 요청이 NOTIFICATION_NOT_FOUND로 처리되는지 검증한다.

        // given
        givenActiveTeam(1L, 2L);
        when(notificationRepository.findByIdAndUserIdAndTeamIdAndDeletedAtIsNull(10L, 1L, 2L))
                .thenReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> service.delete(1L, 10L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
    }

    @Test
    @DisplayName("ACTIVE 팀이 없으면 알림 삭제 요청은 실패한다")
    void deleteFailsWithoutActiveTeam() {
        // 테스트 목적:
        // 현재 ACTIVE 팀이 없는 사용자의 알림 삭제 요청이
        // 기존 TEAM_MEMBERSHIP_REQUIRED 정책으로 거부되는지 검증한다.

        // given
        when(teamMemberRepository.findByUserIdAndMembershipStatusWithTeam(1L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> service.delete(1L, 10L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_MEMBERSHIP_REQUIRED));
        verify(notificationRepository, never()).findByIdAndUserIdAndTeamIdAndDeletedAtIsNull(
                anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("false 읽음 요청은 거부한다")
    void markAsReadRejectsFalseStatus() {
        // 테스트 목적:
        // 알림 읽음 처리 API는 true 요청만 허용하고
        // false 값은 INVALID_NOTIFICATION_READ_STATUS로 거부하는지 검증한다.

        // when, then
        assertThatThrownBy(() -> service.markAsRead(1L, 10L, new NotificationReadRequest(false)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode())
                                .isEqualTo(NotificationErrorCode.INVALID_NOTIFICATION_READ_STATUS));
        verify(notificationRepository, never()).findByIdAndUserIdAndTeamIdAndDeletedAtIsNull(
                anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("누락된 읽음 상태 요청은 거부한다")
    void markAllAsReadRejectsMissingStatus() {
        // 테스트 목적:
        // 알림 읽음 처리 요청에서 isRead가 누락되면
        // INVALID_NOTIFICATION_READ_STATUS로 거부되는지 검증한다.

        // when, then
        assertThatThrownBy(() -> service.markAllAsRead(1L, new NotificationReadRequest(null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode())
                                .isEqualTo(NotificationErrorCode.INVALID_NOTIFICATION_READ_STATUS));
        verify(notificationRepository, never()).markUnreadAsReadUntilId(anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("현재 사용자 알림을 모두 읽음 처리한다")
    void markAllAsRead() {
        // 테스트 목적:
        // 현재 ACTIVE 팀의 본인 미읽음 알림만 호출 시점의 마지막 알림 ID까지 읽음 처리하고
        // 처리 건수와 남은 미읽음 수를 반환하는지 검증한다.

        // given
        givenActiveTeam(1L, 2L);
        when(notificationRepository.findMaxIdByUserIdAndTeamId(1L, 2L)).thenReturn(50L);
        when(notificationRepository.markUnreadAsReadUntilId(1L, 2L, 50L)).thenReturn(3);
        when(notificationRepository.countUnreadByUserIdAndTeamId(1L, 2L)).thenReturn(1L);

        // when
        NotificationsReadResponse response = service.markAllAsRead(1L, new NotificationReadRequest(true));

        // then
        assertThat(response.readCount()).isEqualTo(3L);
        assertThat(response.unreadCount()).isEqualTo(1L);
        verify(notificationRepository).markUnreadAsReadUntilId(1L, 2L, 50L);
    }

    @Test
    @DisplayName("읽을 알림이 없어도 모두 읽음 요청은 성공한다")
    void markAllAsReadWhenEmpty() {
        // 테스트 목적:
        // 현재 ACTIVE 팀의 본인 알림이 이미 모두 읽힌 경우
        // readCount 0으로 정상 응답하는지 검증한다.

        // given
        givenActiveTeam(1L, 2L);
        when(notificationRepository.findMaxIdByUserIdAndTeamId(1L, 2L)).thenReturn(50L);
        when(notificationRepository.markUnreadAsReadUntilId(1L, 2L, 50L)).thenReturn(0);

        // when
        NotificationsReadResponse response = service.markAllAsRead(1L, new NotificationReadRequest(true));

        // then
        assertThat(response.readCount()).isZero();
        assertThat(response.unreadCount()).isZero();
        verify(notificationRepository).markUnreadAsReadUntilId(1L, 2L, 50L);
    }

    @Test
    @DisplayName("ACTIVE 팀이 없으면 알림 요청은 실패한다")
    void notificationRequestFailsWithoutActiveTeam() {
        // 테스트 목적:
        // 현재 ACTIVE 팀이 없는 사용자가 알림을 조회하거나 읽음 처리할 때
        // 기존 TEAM_MEMBERSHIP_REQUIRED 정책으로 거부되는지 검증한다.

        // given
        when(teamMemberRepository.findByUserIdAndMembershipStatusWithTeam(1L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> service.getNotifications(1L, null, 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_MEMBERSHIP_REQUIRED));
        verify(notificationRepository, never()).findPageByUserIdAndTeamId(anyLong(), anyLong(), any(), any());
    }

    private TeamMember member(Team team, Long userId, Long memberId, String displayName) {
        User user = withId(User.create(), userId);
        return withId(TeamMember.createMember(user, team, displayName), memberId);
    }

    private void givenActiveTeam(Long userId, Long teamId) {
        Team team = withId(Team.create("미티팀"), teamId);
        TeamMember member = member(team, userId, 10L, "사용자");
        when(teamMemberRepository.findByUserIdAndMembershipStatusWithTeam(userId, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(member));
    }

    private Notification notification(Long notificationId, Long teamId, Long userId, String body, boolean isRead) {
        Team team = withId(Team.create("미티팀"), teamId);
        User user = withId(User.create(), userId);
        Notification notification = withId(Notification.create(
                team,
                user,
                NotificationType.MEETING_STARTED,
                "MEETING_STARTED:" + notificationId,
                NotificationReferenceType.MEETING,
                100L,
                body
        ), notificationId);
        if (isRead) {
            notification.markAsRead();
        }
        ReflectionTestUtils.setField(notification, "createdAt", java.time.LocalDateTime.of(2026, 10, 9, 10, 0));
        return notification;
    }
}
