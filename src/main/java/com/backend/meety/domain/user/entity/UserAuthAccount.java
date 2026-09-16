package com.backend.meety.domain.user.entity;

import com.backend.meety.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "user_auth_accounts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_auth_accounts_provider_provider_user_id",
                columnNames = {"provider", "provider_user_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAuthAccount extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "provider", nullable = false, length = 30)
    private String provider;

    @Column(name = "provider_user_id", nullable = false, length = 100)
    private String providerUserId;

    private UserAuthAccount(User user, String provider, String providerUserId) {
        this.user = user;
        this.provider = provider;
        this.providerUserId = providerUserId;
    }

    public static UserAuthAccount of(User user, String provider, String providerUserId) {
        return new UserAuthAccount(user, provider, providerUserId);
    }

    public void withdraw(LocalDateTime withdrawnAt) {
        markDeleted(withdrawnAt);
    }

    public void reactivate() {
        restoreDeleted();
    }

    public boolean isWithdrawn() {
        return getDeletedAt() != null;
    }
}
