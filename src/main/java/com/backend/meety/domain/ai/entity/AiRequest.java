package com.backend.meety.domain.ai.entity;

import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "ai_requests")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiRequest extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_member_id")
    private TeamMember teamMember;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 30)
    private AiRequestType requestType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private AiRequestStatus status = AiRequestStatus.ACCEPTED;

    @Column(name = "retry_count", nullable = false)
    private Long retryCount = 0L;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", length = 30)
    private AiFailureReason failureReason;

    private AiRequest(Team team, TeamMember teamMember, String idempotencyKey, AiRequestType requestType) {
        this.team = team;
        this.teamMember = teamMember;
        this.idempotencyKey = idempotencyKey;
        this.requestType = requestType;
    }

    public static AiRequest create(Team team, TeamMember teamMember,
                                   String idempotencyKey, AiRequestType requestType) {
        return new AiRequest(team, teamMember, idempotencyKey, requestType);
    }

    public boolean isAccepted() {
        return status == AiRequestStatus.ACCEPTED;
    }

    public void markProcessing() {
        this.status = AiRequestStatus.PROCESSING;
    }

    public void markCompleted() {
        this.status = AiRequestStatus.COMPLETED;
    }

    public void markFailed(AiFailureReason failureReason) {
        this.status = AiRequestStatus.FAILED;
        this.failureReason = failureReason;
    }

    public void markAccepted() {
        this.status = AiRequestStatus.ACCEPTED;
    }

    public void increaseRetryCount() {
        this.retryCount++;
    }
}
