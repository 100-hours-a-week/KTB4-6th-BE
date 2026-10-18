package com.backend.meety.domain.notification.entity;

import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.user.entity.User;
import com.backend.meety.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "notification",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_notification_user_id_idempotency_key",
                columnNames = {"user_id", "idempotency_key"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private NotificationType type;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", length = 30)
    private NotificationReferenceType referenceType;

    @Column(name = "reference_id")
    private Long referenceId;

    @Column(name = "body", nullable = false, length = 255)
    private String body;

    @Column(name = "is_read", nullable = false)
    private boolean isRead = false;

    private Notification(
            Team team,
            User user,
            NotificationType type,
            String idempotencyKey,
            NotificationReferenceType referenceType,
            Long referenceId,
            String body
    ) {
        this.team = team;
        this.user = user;
        this.type = type;
        this.idempotencyKey = idempotencyKey;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.body = body;
        this.isRead = false;
    }

    public static Notification create(
            Team team,
            User user,
            NotificationType type,
            String idempotencyKey,
            NotificationReferenceType referenceType,
            Long referenceId,
            String body
    ) {
        return new Notification(team, user, type, idempotencyKey, referenceType, referenceId, body);
    }

    public boolean markAsRead() {
        if (isRead) {
            return false;
        }
        isRead = true;
        return true;
    }

    public void delete(LocalDateTime deletedAt) {
        markDeleted(deletedAt);
    }
}
