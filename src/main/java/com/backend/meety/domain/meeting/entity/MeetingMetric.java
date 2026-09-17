package com.backend.meety.domain.meeting.entity;

import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "meeting_metrics")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MeetingMetric extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ai_request_id", nullable = false, unique = true)
    private AiRequest aiRequest;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meeting_id", nullable = false)
    private Meeting meeting;

    @Column(name = "metrics", nullable = false, columnDefinition = "JSON")
    private String metrics;

    @Column(name = "speech_balance_score", precision = 5, scale = 2, columnDefinition = "DECIMAL(5,2)")
    private BigDecimal speechBalanceScore;
}
