package com.backend.meety.domain.notification.service;

import com.backend.meety.domain.notification.entity.Notification;
import com.backend.meety.domain.notification.event.NotificationCreatedEvent;
import com.backend.meety.domain.notification.repository.NotificationRepository;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationWriter {

    private final NotificationRepository notificationRepository;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void create(NotificationWriteCommand command) {
        Team team = entityManager.getReference(Team.class, command.teamId());
        User user = entityManager.getReference(User.class, command.userId());
        Notification notification = notificationRepository.saveAndFlush(Notification.create(
                team,
                user,
                command.type(),
                command.idempotencyKey(),
                command.referenceType(),
                command.referenceId(),
                command.body()
        ));
        eventPublisher.publishEvent(new NotificationCreatedEvent(
                notification.getId(),
                command.userId(),
                notification.getType(),
                notification.getBody(),
                notification.getReferenceType(),
                notification.getReferenceId(),
                notification.isRead(),
                notification.getCreatedAt()
        ));
    }
}
