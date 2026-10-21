package com.backend.meety.domain.credit.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static com.backend.meety.domain.recording.RecordingFixtures.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CreditLedgerRecordingRestoreTest {
    @Test
    @DisplayName("녹음 보상 원장은 RESTORE·RECORDING과 세션별 멱등키 및 양수 금액을 기록한다")
    void recordingRestoreLedger() {
        // 테스트 목적:
        // 차감 원장과 별개로 보상 거래가 정확한 출처와 잔액으로 추적되는지 검증한다.
        // given
        var team = team();
        // when
        var ledger = CreditLedger.restoreForRecording(team, 700L, 20L, 100L);
        // then
        assertThat(ledger.getIdempotencyKey()).isEqualTo("RESTORE:RECORDING:700");
        assertThat(ledger.getType()).isEqualTo(CreditTransactionType.RESTORE);
        assertThat(ledger.getSourceType()).isEqualTo(CreditSourceType.RECORDING);
        assertThat(ledger.getSourceId()).isEqualTo(700L);
        assertThat(ledger.getAmount()).isEqualTo(20L);
        assertThat(ledger.getBalanceAfter()).isEqualTo(100L);
        assertThat(ledger.getTeam()).isSameAs(team);
    }
}
