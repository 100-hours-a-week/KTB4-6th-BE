package com.backend.meety.domain.credit.service;

import static com.backend.meety.domain.recording.RecordingFixtures.team;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.credit.TestCreditPolicy;
import com.backend.meety.domain.credit.entity.CreditLedger;
import com.backend.meety.domain.credit.entity.CreditSourceType;
import com.backend.meety.domain.credit.entity.CreditTransactionType;
import com.backend.meety.domain.credit.entity.TeamCredit;
import com.backend.meety.domain.credit.repository.CreditLedgerRepository;
import com.backend.meety.domain.credit.repository.TeamCreditRepository;
import com.backend.meety.domain.team.entity.Team;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WeeklyCreditGrantServiceTest {

    private static final String WEEK_KEY = "2026-W40";

    private final TeamCreditRepository credits = mock(TeamCreditRepository.class);
    private final CreditLedgerRepository ledgers = mock(CreditLedgerRepository.class);
    private final WeeklyCreditGrantService service =
            new WeeklyCreditGrantService(credits, ledgers, TestCreditPolicy.DEFAULT);

    private final Team team = team();

    private TeamCredit creditWith(long balance) {
        TeamCredit credit = TeamCredit.create(team, balance, TestCreditPolicy.MAX_BALANCE);
        when(credits.findByTeamIdForUpdate(team.getId())).thenReturn(Optional.of(credit));
        return credit;
    }

    @Test
    @DisplayName("주간 크레딧을 적립하고 원장을 남긴다")
    void grantsAndRecordsLedger() {
        TeamCredit credit = creditWith(100L);

        boolean granted = service.grant(team.getId(), WEEK_KEY);

        assertThat(granted).isTrue();
        assertThat(credit.getBalance()).isEqualTo(100L + TestCreditPolicy.WEEKLY_GRANT);
        ArgumentCaptor<CreditLedger> captor = ArgumentCaptor.forClass(CreditLedger.class);
        verify(ledgers).save(captor.capture());
        CreditLedger ledger = captor.getValue();
        assertThat(ledger.getIdempotencyKey()).isEqualTo("EARN:SCHEDULE:2:2026-W40");
        assertThat(ledger.getType()).isEqualTo(CreditTransactionType.EARN);
        assertThat(ledger.getSourceType()).isEqualTo(CreditSourceType.SCHEDULE);
        assertThat(ledger.getSourceId()).isNull();
        assertThat(ledger.getAmount()).isEqualTo(TestCreditPolicy.WEEKLY_GRANT);
        assertThat(ledger.getBalanceAfter()).isEqualTo(100L + TestCreditPolicy.WEEKLY_GRANT);
    }

    @Test
    @DisplayName("같은 주차에 이미 적립했으면 건너뛴다")
    void skipsAlreadyGrantedWeek() {
        TeamCredit credit = creditWith(100L);
        when(ledgers.existsByIdempotencyKey("EARN:SCHEDULE:2:2026-W40")).thenReturn(true);

        assertThat(service.grant(team.getId(), WEEK_KEY)).isFalse();
        assertThat(credit.getBalance()).isEqualTo(100L);
        verify(ledgers, never()).save(any());
    }

    @Test
    @DisplayName("상한을 넘는 만큼은 잘라서 적립하고 실제 적립분을 원장에 남긴다")
    void capsAtMaxBalance() {
        TeamCredit credit = creditWith(TestCreditPolicy.MAX_BALANCE - 20L);

        assertThat(service.grant(team.getId(), WEEK_KEY)).isTrue();
        assertThat(credit.getBalance()).isEqualTo(TestCreditPolicy.MAX_BALANCE);
        ArgumentCaptor<CreditLedger> captor = ArgumentCaptor.forClass(CreditLedger.class);
        verify(ledgers).save(captor.capture());
        assertThat(captor.getValue().getAmount()).isEqualTo(20L);
    }

    @Test
    @DisplayName("이미 상한이면 원장을 남기지 않는다")
    void skipsLedgerWhenAtMaxBalance() {
        creditWith(TestCreditPolicy.MAX_BALANCE);

        assertThat(service.grant(team.getId(), WEEK_KEY)).isFalse();
        verify(ledgers, never()).save(any());
    }

    @Test
    @DisplayName("크레딧 행이 없으면 건너뛴다")
    void skipsWhenCreditMissing() {
        when(credits.findByTeamIdForUpdate(team.getId())).thenReturn(Optional.empty());

        assertThat(service.grant(team.getId(), WEEK_KEY)).isFalse();
        verify(ledgers, never()).save(any());
    }
}
