package com.backend.meety.domain.credit.entity;

import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "team_credits")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TeamCredit extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @Column(name = "balance", nullable = false)
    private Long balance;

    private TeamCredit(Team team, long balance) {
        this.team = team;
        this.balance = balance;
    }

    public static TeamCredit create(Team team, long initialBalance, long maxBalance) {
        return new TeamCredit(team, Math.min(initialBalance, maxBalance));
    }

    public long earn(long amount, long maxBalance) {
        long available = Math.max(0L, maxBalance - balance);
        long earned = Math.min(amount, available);
        balance += earned;
        return earned;
    }

    public boolean canUse(long amount) {
        return balance >= amount;
    }

    public void use(long amount) {
        balance -= amount;
    }
}
