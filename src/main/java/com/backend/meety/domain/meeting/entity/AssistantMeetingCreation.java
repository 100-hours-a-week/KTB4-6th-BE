package com.backend.meety.domain.meeting.entity;

import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.global.entity.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "assistant_meeting_creations", uniqueConstraints = {
        @UniqueConstraint(name = "uk_assistant_meeting_creation_request", columnNames = "ai_request_id"),
        @UniqueConstraint(name = "uk_assistant_meeting_creation_meeting", columnNames = "meeting_id")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AssistantMeetingCreation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ai_request_id", nullable = false)
    private AiRequest aiRequest;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meeting_id", nullable = false)
    private Meeting meeting;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    private AssistantMeetingCreation(AiRequest aiRequest, Meeting meeting, String requestHash) {
        this.aiRequest = aiRequest;
        this.meeting = meeting;
        this.requestHash = requestHash;
    }

    public static AssistantMeetingCreation create(AiRequest aiRequest, Meeting meeting, String requestHash) {
        return new AssistantMeetingCreation(aiRequest, meeting, requestHash);
    }
}
