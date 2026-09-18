package com.backend.meety.domain.team.entity;

import com.backend.meety.domain.user.entity.User;
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
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "team_members",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_team_members_team_id_display_name",
                columnNames = {"team_id", "display_name"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TeamMember extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @Column(name = "display_name", nullable = false, length = 10)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    private TeamMemberRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "membership_status", nullable = false, length = 30)
    private MembershipStatus membershipStatus = MembershipStatus.ACTIVE;

    private TeamMember(User user, Team team, String displayName, TeamMemberRole role) {
        this.user = user;
        this.team = team;
        this.displayName = displayName;
        this.role = role;
    }

    public static TeamMember createLeader(User user, Team team, String displayName) {
        return new TeamMember(user, team, displayName, TeamMemberRole.LEADER);
    }

    public static TeamMember createMember(User user, Team team, String displayName) {
        return new TeamMember(user, team, displayName, TeamMemberRole.MEMBER);
    }

    public void releaseKick() {
        this.membershipStatus = MembershipStatus.LEFT;
    }

    public boolean isLeader() {
        return role == TeamMemberRole.LEADER;
    }

    public void leave(LocalDateTime leftAt) {
        this.membershipStatus = MembershipStatus.LEFT;
        markDeleted(leftAt);
    }

    public void kick(LocalDateTime kickedAt) {
        this.membershipStatus = MembershipStatus.KICKED;
        markDeleted(kickedAt);
    }
}
