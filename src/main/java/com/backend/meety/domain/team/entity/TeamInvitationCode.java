package com.backend.meety.domain.team.entity;

import com.backend.meety.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "team_invitation_codes",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_team_invitation_codes_code",
                columnNames = "code"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TeamInvitationCode extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @Column(name = "code", nullable = false, length = 8, columnDefinition = "CHAR(8)")
    private String code;

    private TeamInvitationCode(Team team, String code) {
        this.team = team;
        this.code = code;
    }

    public static TeamInvitationCode create(Team team, String code) {
        return new TeamInvitationCode(team, code);
    }

    public void revoke(LocalDateTime revokedAt) {
        markDeleted(revokedAt);
    }

    public boolean isCreatedOn(LocalDate date) {
        return getCreatedAt().toLocalDate().isEqual(date);
    }
}
