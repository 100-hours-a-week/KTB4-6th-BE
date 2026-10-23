package com.backend.meety.domain.notification.service;

import com.backend.meety.domain.notification.entity.Notification;
import com.backend.meety.domain.notification.event.NotificationCreatedEvent;
import com.backend.meety.domain.notification.repository.NotificationRepository;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
        Notification notification = notificationRepository.saveAndFlush(toNotification(command));
        publishCreated(notification, command.userId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createAll(List<NotificationWriteCommand> commands) {
        if (commands.isEmpty()) {
            return;
        }
        Set<Long> notifiedUserIds = new HashSet<>(notificationRepository.findUserIdsByIdempotencyKeyAndUserIdIn(
                commands.getFirst().idempotencyKey(),
                commands.stream().map(NotificationWriteCommand::userId).toList()));
        List<NotificationWriteCommand> newCommands = commands.stream()
                .filter(command -> !notifiedUserIds.contains(command.userId()))
                .toList();
        if (newCommands.isEmpty()) {
            return;
        }
        List<Notification> notifications = notificationRepository.saveAllAndFlush(
                newCommands.stream().map(this::toNotification).toList());
        for (int i = 0; i < notifications.size(); i++) {
            publishCreated(notifications.get(i), newCommands.get(i).userId());
        }
    }

    private Notification toNotification(NotificationWriteCommand command) {
        Team team = entityManager.getReference(Team.class, command.teamId());
        User user = entityManager.getReference(User.class, command.userId());
        return Notification.create(
                team,
                user,
                command.type(),
                command.idempotencyKey(),
                command.referenceType(),
                command.referenceId(),
                command.body()
        );
    }

    private void publishCreated(Notification notification, Long userId) {
        eventPublisher.publishEvent(new NotificationCreatedEvent(
                notification.getId(),
                userId,
                notification.getType(),
                notification.getBody(),
                notification.getReferenceType(),
                notification.getReferenceId(),
                notification.isRead(),
                notification.getCreatedAt()
        ));
    }
}
