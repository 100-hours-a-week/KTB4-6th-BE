package com.backend.meety.domain.credit.entity;

import com.backend.meety.domain.team.entity.Team;
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
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "credit_ledgers")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreditLedger extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @Column(name = "source_id")
    private Long sourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 30)
    private CreditSourceType sourceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private CreditTransactionType type;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "balance_after", nullable = false)
    private Long balanceAfter;

    /**
     * 멱등키를 직접 지정해 원장 행을 만든다.
     * 정기 적립처럼 원본 레코드가 없어 {행위}:{원본타입}:{원본ID} 형식으로 표현할 수 없는 거래에 사용한다.
     */
    private static CreditLedger create(Team team, String idempotencyKey, CreditTransactionType type,
            CreditSourceType sourceType, Long sourceId, long amount, long balanceAfter) {
        CreditLedger ledger = new CreditLedger();
        ledger.team = team;
        ledger.idempotencyKey = idempotencyKey;
        ledger.sourceId = sourceId;
        ledger.sourceType = sourceType;
        ledger.type = type;
        ledger.amount = type == CreditTransactionType.USE ? -Math.abs(amount) : Math.abs(amount);
        ledger.balanceAfter = balanceAfter;
        return ledger;
    }

    /**
     * 원본 레코드가 있는 거래의 원장 행을 만든다. 멱등키는 {행위}:{원본타입}:{원본ID}로 유도한다.
     */
    private static CreditLedger create(Team team, CreditTransactionType type, CreditSourceType sourceType,
            Long sourceId, long amount, long balanceAfter) {
        Objects.requireNonNull(sourceId, "원본 식별자 없이는 멱등키를 유도할 수 없습니다.");
        return create(team, keyOf(type, sourceType, sourceId),
                type, sourceType, sourceId, amount, balanceAfter);
    }

    public static CreditLedger earnForTeamCreate(Team team, long amount, long balanceAfter) {
        return create(team, CreditTransactionType.EARN, CreditSourceType.TEAM_CREATE,
                team.getId(), amount, balanceAfter);
    }

    public static String keyOf(CreditTransactionType type, CreditSourceType sourceType, Long sourceId) {
        return type + ":" + sourceType + ":" + sourceId;
    }

    public static CreditLedger restoreForSummary(Team team, Long aiRequestId, long amount, long balanceAfter) {
        return create(team, CreditTransactionType.RESTORE, CreditSourceType.AI_SUMMARY,
                aiRequestId, amount, balanceAfter);
    }

    public static CreditLedger useForSummary(Team team, Long aiRequestId, long amount, long balanceAfter) {
        return create(team, CreditTransactionType.USE, CreditSourceType.AI_SUMMARY,
                aiRequestId, amount, balanceAfter);
    }

    public static CreditLedger useForRecording(Team team, Long recordingSessionId, long amount, long balanceAfter) {
        return create(team, CreditTransactionType.USE, CreditSourceType.RECORDING,
                recordingSessionId, amount, balanceAfter);
    }
}
