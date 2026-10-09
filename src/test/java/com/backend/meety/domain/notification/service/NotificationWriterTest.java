package com.backend.meety.domain.notification.service;

import static com.backend.meety.domain.recording.RecordingFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.notification.entity.Notification;
import com.backend.meety.domain.notification.entity.NotificationReferenceType;
import com.backend.meety.domain.notification.entity.NotificationType;
import com.backend.meety.domain.notification.event.NotificationCreatedEvent;
import com.backend.meety.domain.notification.repository.NotificationRepository;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class NotificationWriterTest {

    @Test
    @DisplayName("알림 저장 Writer는 별도 트랜잭션에서 실행된다")
    void writerUsesRequiresNewTransaction() throws NoSuchMethodException {
        // 테스트 목적:
        // 알림 저장 실패가 원본 비즈니스 트랜잭션에 영향을 주지 않도록
        // 알림 저장 메서드가 REQUIRES_NEW 트랜잭션 경계를 가지는지 검증한다.

        // given
        Method method = NotificationWriter.class.getMethod("create", NotificationWriteCommand.class);

        // when
        Transactional transactional = method.getAnnotation(Transactional.class);

        // then
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    @DisplayName("알림 저장 성공 후 NotificationCreatedEvent를 발행한다")
    void createPublishesNotificationCreatedEventAfterSaveSuccess() {
        // 테스트 목적:
        // 알림 DB 저장과 flush가 성공한 경우에만
        // 실시간 SSE 전송의 기준이 되는 NotificationCreatedEvent를 발행하는지 검증한다.

        // given
        NotificationRepository notificationRepository = mock(NotificationRepository.class);
        EntityManager entityManager = mock(EntityManager.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        NotificationWriter writer = new NotificationWriter(notificationRepository, entityManager, eventPublisher);
        Team team = withId(Team.create("미티팀"), 2L);
        User user = withId(User.create(), 1L);
        Notification notification = notification(team, user);
        when(entityManager.getReference(Team.class, 2L)).thenReturn(team);
        when(entityManager.getReference(User.class, 1L)).thenReturn(user);
        when(notificationRepository.saveAndFlush(any(Notification.class))).thenReturn(notification);

        // when
        writer.create(new NotificationWriteCommand(
                2L,
                1L,
                NotificationType.MEMBER_JOINED,
                "MEMBER_JOINED:event-1",
                NotificationReferenceType.TEAM,
                null,
                "test2 님이 팀에 합류했습니다"
        ));

        // then
        ArgumentCaptor<NotificationCreatedEvent> captor = ArgumentCaptor.forClass(NotificationCreatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new NotificationCreatedEvent(
                15L,
                1L,
                NotificationType.MEMBER_JOINED,
                "test2 님이 팀에 합류했습니다",
                NotificationReferenceType.TEAM,
                null,
                false,
                LocalDateTime.of(2026, 10, 9, 13, 30, 12)
        ));
    }

    @Test
    @DisplayName("알림 저장 실패 시 NotificationCreatedEvent를 발행하지 않는다")
    void createDoesNotPublishEventWhenSaveFails() {
        // 테스트 목적:
        // 알림 DB 저장이 실패한 경우 저장되지 않은 알림을
        // 실시간 SSE로 잘못 전송하지 않는지 검증한다.

        // given
        NotificationRepository notificationRepository = mock(NotificationRepository.class);
        EntityManager entityManager = mock(EntityManager.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        NotificationWriter writer = new NotificationWriter(notificationRepository, entityManager, eventPublisher);
        Team team = withId(Team.create("미티팀"), 2L);
        User user = withId(User.create(), 1L);
        when(entityManager.getReference(Team.class, 2L)).thenReturn(team);
        when(entityManager.getReference(User.class, 1L)).thenReturn(user);
        when(notificationRepository.saveAndFlush(any(Notification.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        // when, then
        assertThatThrownBy(() -> writer.create(new NotificationWriteCommand(
                2L,
                1L,
                NotificationType.MEMBER_JOINED,
                "MEMBER_JOINED:event-1",
                NotificationReferenceType.TEAM,
                null,
                "test2 님이 팀에 합류했습니다"
        ))).isInstanceOf(DataIntegrityViolationException.class);
        verify(eventPublisher, never()).publishEvent(any());
    }

    private Notification notification(Team team, User user) {
        Notification notification = withId(Notification.create(
                team,
                user,
                NotificationType.MEMBER_JOINED,
                "MEMBER_JOINED:event-1",
                NotificationReferenceType.TEAM,
                null,
                "test2 님이 팀에 합류했습니다"
        ), 15L);
        ReflectionTestUtils.setField(notification, "createdAt", LocalDateTime.of(2026, 10, 9, 13, 30, 12));
        return notification;
    }
}
