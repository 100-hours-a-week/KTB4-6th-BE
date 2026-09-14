package com.backend.meety.domain.report.entity;

import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.team.entity.Team;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "analysis_reports")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisReport extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ai_request_id", nullable = false, unique = true)
    private AiRequest aiRequest;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "request_body", nullable = false, length = 1000)
    private String requestBody;

    @Column(name = "content_html", columnDefinition = "MEDIUMTEXT")
    private String contentHtml;

    @Column(name = "insight_markdown", columnDefinition = "MEDIUMTEXT")
    private String insightMarkdown;

    @Column(name = "request_hash", nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String requestHash;
}
