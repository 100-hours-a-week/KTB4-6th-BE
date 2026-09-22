package com.backend.meety.domain.credit.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.meety.domain.credit.CreditPolicy;
import com.backend.meety.domain.team.entity.Team;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TeamCreditTest {

    private static final Team TEAM = Team.create("Meety Team");

    @Test
    @DisplayName("적립하면 잔액이 증가하고 적립된 양을 반환한다")
    void earn() {
        TeamCredit credit = TeamCredit.create(TEAM, 50L);

        long earned = credit.earn(50L);

        assertThat(earned).isEqualTo(50L);
        assertThat(credit.getBalance()).isEqualTo(100L);
    }

    @Test
    @DisplayName("상한을 넘는 적립은 최대 한도까지만 반영된다")
    void earnUpToMaxBalance() {
        TeamCredit credit = TeamCredit.create(TEAM, CreditPolicy.MAX_BALANCE - 20L);

        long earned = credit.earn(50L);

        assertThat(earned).isEqualTo(20L);
        assertThat(credit.getBalance()).isEqualTo(CreditPolicy.MAX_BALANCE);
    }

    @Test
    @DisplayName("이미 상한이면 적립되지 않는다")
    void earnNothingWhenFull() {
        TeamCredit credit = TeamCredit.create(TEAM, CreditPolicy.MAX_BALANCE);

        long earned = credit.earn(50L);

        assertThat(earned).isZero();
        assertThat(credit.getBalance()).isEqualTo(CreditPolicy.MAX_BALANCE);
    }

    @Test
    @DisplayName("상한을 넘는 초기 잔액으로는 생성할 수 없다")
    void createCappedAtMaxBalance() {
        TeamCredit credit = TeamCredit.create(TEAM, CreditPolicy.MAX_BALANCE + 200L);

        assertThat(credit.getBalance()).isEqualTo(CreditPolicy.MAX_BALANCE);
    }
}
