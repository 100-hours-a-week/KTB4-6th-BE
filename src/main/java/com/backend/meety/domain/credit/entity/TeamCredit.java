package com.backend.meety.domain.credit.entity;

import com.backend.meety.domain.credit.CreditPolicy;
import com.backend.meety.domain.credit.exception.CreditErrorCode;
import com.backend.meety.domain.credit.exception.CreditException;
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

    public static TeamCredit create(Team team, long initialBalance) {
        return new TeamCredit(team, Math.min(initialBalance, CreditPolicy.MAX_BALANCE));
    }

    /**
     * 상한을 초과하지 않도록 적립하고, 실제 적립된 양을 반환한다.
     */
    public long earn(long amount) {
        long earned = Math.min(amount, CreditPolicy.MAX_BALANCE - balance);
        balance += earned;
        return earned;
    }

    public void validateCanUse(long amount) {
        if (balance < amount) {
            throw new CreditException(CreditErrorCode.INSUFFICIENT_CREDIT);
        }
    }

    public void use(long amount) {
        validateCanUse(amount);
        balance -= amount;
    }
}
