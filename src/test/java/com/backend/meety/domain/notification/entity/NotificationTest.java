package com.backend.meety.domain.notification.entity;

import static com.backend.meety.domain.recording.RecordingFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;

import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.user.entity.User;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NotificationTest {

    @Test
    @DisplayName("알림 생성 시 읽지 않음 상태와 참조 정보를 저장한다")
    void createNotification() {
        // 테스트 목적:
        // 알림 생성 factory가 팀, 사용자, 타입, 멱등키, 참조, 본문을 저장하고
        // 기본 읽음 상태를 false로 유지하는지 검증한다.

        // given
        Team team = withId(Team.create("미티팀"), 2L);
        User user = withId(User.create(), 1L);

        // when
        Notification notification = Notification.create(
                team,
                user,
                NotificationType.MEETING_STARTED,
                "MEETING_STARTED:700",
                NotificationReferenceType.MEETING,
                100L,
                "시작자 님이 회의를 시작했습니다"
        );

        // then
        assertThat(notification.getTeam()).isEqualTo(team);
        assertThat(notification.getUser()).isEqualTo(user);
        assertThat(notification.getType()).isEqualTo(NotificationType.MEETING_STARTED);
        assertThat(notification.getIdempotencyKey()).isEqualTo("MEETING_STARTED:700");
        assertThat(notification.getReferenceType()).isEqualTo(NotificationReferenceType.MEETING);
        assertThat(notification.getReferenceId()).isEqualTo(100L);
        assertThat(notification.getBody()).isEqualTo("시작자 님이 회의를 시작했습니다");
        assertThat(notification.isRead()).isFalse();
    }

    @Test
    @DisplayName("읽지 않은 알림은 읽음 상태로 변경된다")
    void markAsRead() {
        // 테스트 목적:
        // 알림 읽음 처리 시 Entity의 isRead 상태가 true로 변경되고
        // 실제 상태 변경 여부를 반환하는지 검증한다.

        // given
        Team team = withId(Team.create("미티팀"), 2L);
        User user = withId(User.create(), 1L);
        Notification notification = Notification.create(
                team,
                user,
                NotificationType.MEETING_STARTED,
                "MEETING_STARTED:700",
                NotificationReferenceType.MEETING,
                100L,
                "시작자 님이 회의를 시작했습니다"
        );

        // when
        boolean changed = notification.markAsRead();

        // then
        assertThat(changed).isTrue();
        assertThat(notification.isRead()).isTrue();
    }

    @Test
    @DisplayName("이미 읽은 알림 읽음 처리는 멱등하다")
    void markAsReadAlreadyRead() {
        // 테스트 목적:
        // 이미 읽음 상태인 알림에 다시 읽음 처리를 요청해도
        // 상태가 유지되고 변경 없음으로 반환되는지 검증한다.

        // given
        Team team = withId(Team.create("미티팀"), 2L);
        User user = withId(User.create(), 1L);
        Notification notification = Notification.create(
                team,
                user,
                NotificationType.MEETING_STARTED,
                "MEETING_STARTED:700",
                NotificationReferenceType.MEETING,
                100L,
                "시작자 님이 회의를 시작했습니다"
        );
        notification.markAsRead();

        // when
        boolean changed = notification.markAsRead();

        // then
        assertThat(changed).isFalse();
        assertThat(notification.isRead()).isTrue();
    }

    @Test
    @DisplayName("알림 삭제는 deletedAt을 설정한다")
    void deleteNotification() {
        // 테스트 목적:
        // 알림 삭제가 hard delete가 아니라
        // BaseEntity의 deletedAt을 설정하는 soft delete로 표현되는지 검증한다.

        // given
        Team team = withId(Team.create("미티팀"), 2L);
        User user = withId(User.create(), 1L);
        Notification notification = Notification.create(
                team,
                user,
                NotificationType.MEETING_STARTED,
                "MEETING_STARTED:700",
                NotificationReferenceType.MEETING,
                100L,
                "시작자 님이 회의를 시작했습니다"
        );
        LocalDateTime deletedAt = LocalDateTime.of(2026, 10, 9, 12, 30);

        // when
        notification.delete(deletedAt);

        // then
        assertThat(notification.getDeletedAt()).isEqualTo(deletedAt);
    }

    @Test
    @DisplayName("알림 테이블은 사용자와 멱등키 조합 UNIQUE 제약을 가진다")
    void notificationTableHasUserIdempotencyUniqueConstraint() {
        // 테스트 목적:
        // 동일 이벤트가 재처리되어도 같은 사용자에게 알림이 중복 저장되지 않도록
        // Entity 매핑에 user_id, idempotency_key UNIQUE 제약이 선언되어 있는지 검증한다.

        // given
        Table table = Notification.class.getAnnotation(Table.class);

        // when
        UniqueConstraint constraint = Arrays.stream(table.uniqueConstraints())
                .filter(found -> found.name().equals("uk_notification_user_id_idempotency_key"))
                .findFirst()
                .orElseThrow();

        // then
        assertThat(constraint.columnNames()).containsExactly("user_id", "idempotency_key");
    }
}
