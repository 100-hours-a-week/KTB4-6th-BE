package com.backend.meety.domain.ai.entity;

import com.backend.meety.domain.meeting.entity.Meeting;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "ai_chatbot_messages")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiChatbotMessage extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ai_request_id", nullable = false, unique = true)
    private AiRequest aiRequest;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meeting_id", nullable = false)
    private Meeting meeting;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_member_id", nullable = false)
    private TeamMember teamMember;

    @Enumerated(EnumType.STRING)
    @Column(name = "input_type", nullable = false, length = 30)
    private ChatInputType inputType;

    @Column(name = "question", nullable = false, length = 1000)
    private String question;

    @Column(name = "answer", columnDefinition = "TEXT")
    private String answer;
}
