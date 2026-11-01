package com.backend.meety.domain.ai.entity;

import com.backend.meety.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "assistant_chat_turns", uniqueConstraints = @UniqueConstraint(
        name = "uk_assistant_chat_turn_request", columnNames = "ai_request_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AssistantChatTurn extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ai_request_id", nullable = false, unique = true)
    private AiRequest aiRequest;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    private AssistantChatConversation conversation;

    @Column(name = "question", nullable = false, length = 1000)
    private String question;

    @Column(name = "answer", columnDefinition = "TEXT")
    private String answer;

    @Column(name = "failure_code", length = 50)
    private String failureCode;

    @Column(name = "meeting_draft", columnDefinition = "JSON")
    private String meetingDraft;

    @Column(name = "referenced_meeting_ids", columnDefinition = "JSON")
    private String referencedMeetingIds;

    private AssistantChatTurn(AiRequest aiRequest, AssistantChatConversation conversation, String question) {
        this.aiRequest = aiRequest;
        this.conversation = conversation;
        this.question = question;
    }

    public static AssistantChatTurn create(AiRequest aiRequest, AssistantChatConversation conversation,
                                           String question) {
        return new AssistantChatTurn(aiRequest, conversation, question);
    }

    public void complete(String answer, String meetingDraft, String referencedMeetingIds) {
        this.answer = answer;
        this.meetingDraft = meetingDraft;
        this.referencedMeetingIds = referencedMeetingIds;
        this.failureCode = null;
    }

    public void fail(String failureCode) {
        this.answer = null;
        this.meetingDraft = null;
        this.referencedMeetingIds = null;
        this.failureCode = failureCode;
    }
}
