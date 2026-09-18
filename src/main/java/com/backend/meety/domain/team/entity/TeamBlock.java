package com.backend.meety.domain.team.entity;

import com.backend.meety.domain.user.entity.User;
import com.backend.meety.global.entity.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "team_blocks")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TeamBlock extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    private TeamBlock(Team team, User user) {
        this.team = team;
        this.user = user;
    }

    public static TeamBlock create(Team team, User user) {
        return new TeamBlock(team, user);
    }

    public void release(LocalDateTime releasedAt) {
        markDeleted(releasedAt);
    }

    public boolean isActive() {
        return getDeletedAt() == null;
    }
}
