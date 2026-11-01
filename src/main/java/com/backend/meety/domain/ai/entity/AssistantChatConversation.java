package com.backend.meety.domain.ai.entity;

import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.global.entity.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "assistant_chat_conversations", uniqueConstraints = @UniqueConstraint(
        name = "uk_assistant_chat_conversation_member_team", columnNames = {"team_member_id", "team_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AssistantChatConversation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_member_id", nullable = false)
    private TeamMember teamMember;

    private AssistantChatConversation(Team team, TeamMember teamMember) {
        this.team = team;
        this.teamMember = teamMember;
    }

    public static AssistantChatConversation create(Team team, TeamMember teamMember) {
        return new AssistantChatConversation(team, teamMember);
    }
}
