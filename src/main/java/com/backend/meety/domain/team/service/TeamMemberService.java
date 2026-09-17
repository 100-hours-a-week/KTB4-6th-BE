package com.backend.meety.domain.team.service;

import com.backend.meety.domain.team.dto.TeamJoinRequest;
import com.backend.meety.domain.team.dto.TeamJoinResponse;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamInvitationCode;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.exception.TeamErrorCode;
import com.backend.meety.domain.team.exception.TeamException;
import com.backend.meety.domain.team.repository.TeamBlockRepository;
import com.backend.meety.domain.team.repository.TeamInvitationCodeRepository;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.domain.team.repository.TeamRepository;
import com.backend.meety.domain.user.entity.User;
import com.backend.meety.domain.user.repository.UserRepository;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TeamMemberService {

    private static final int MAX_TEAM_MEMBERS = 10;

    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final TeamInvitationCodeRepository teamInvitationCodeRepository;
    private final TeamBlockRepository teamBlockRepository;

    /*
     * 잠금 순서 규칙: user 행 → team 행. 멤버십을 변경하는 모든 유스케이스가 이 순서를 지켜야 데드락이 없다.
     * READ_COMMITTED: 두 잠금 사이에 일반 조회(코드로 팀 식별)가 끼므로, REPEATABLE READ면
     * 그 시점 스냅샷이 고정되어 team 잠금 획득 후의 정원·이름·코드 검증이 낡은 상태를 읽는다.
     * 문장마다 최신 커밋을 읽어야 잠금 직렬화가 검증에 반영된다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TeamJoinResponse join(Long userId, TeamJoinRequest request) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new TeamException(TeamErrorCode.USER_NOT_FOUND));
        validateNoActiveTeam(userId);
        String code = request.invitationCode().toUpperCase();
        Team team = resolveTeamByCode(code);
        validateCodeStillActive(code, team.getId());
        validateNotBlocked(team.getId(), userId);
        validateCapacity(team.getId());
        validateDisplayNameAvailable(team.getId(), request.displayName());
        try {
            TeamMember teamMember = teamMemberRepository.save(
                    TeamMember.createMember(user, team, request.displayName()));
            return TeamJoinResponse.of(team, teamMember);
        } catch (DataAccessException e) {
            throw new TeamException(TeamErrorCode.TEAM_MEMBERSHIP_CREATE_FAILED);
        }
    }

    private Team resolveTeamByCode(String code) {
        TeamInvitationCode invitationCode = teamInvitationCodeRepository.findByCodeAndDeletedAtIsNull(code)
                .orElseThrow(() -> new TeamException(TeamErrorCode.INVITATION_CODE_NOT_FOUND));
        Team team = teamRepository.findByIdForUpdate(invitationCode.getTeam().getId())
                .orElseThrow(() -> new TeamException(TeamErrorCode.INVITATION_CODE_NOT_FOUND));
        if (team.isDeleted()) {
            throw new TeamException(TeamErrorCode.INVITATION_CODE_NOT_FOUND);
        }
        return team;
    }

    private void validateCodeStillActive(String code, Long teamId) {
        boolean stillActive = teamInvitationCodeRepository.findByCodeAndDeletedAtIsNull(code)
                .filter(invitationCode -> invitationCode.getTeam().getId().equals(teamId))
                .isPresent();
        if (!stillActive) {
            throw new TeamException(TeamErrorCode.INVITATION_CODE_NOT_FOUND);
        }
    }

    private void validateNoActiveTeam(Long userId) {
        if (teamMemberRepository.existsByUserIdAndMembershipStatus(userId, MembershipStatus.ACTIVE)) {
            throw new TeamException(TeamErrorCode.ACTIVE_TEAM_ALREADY_EXISTS);
        }
    }

    private void validateNotBlocked(Long teamId, Long userId) {
        if (teamBlockRepository.existsByTeamIdAndUserIdAndDeletedAtIsNull(teamId, userId)) {
            throw new TeamException(TeamErrorCode.TEAM_BLOCKED_USER);
        }
    }

    private void validateCapacity(Long teamId) {
        if (teamMemberRepository.countByTeamIdAndMembershipStatus(teamId, MembershipStatus.ACTIVE)
                >= MAX_TEAM_MEMBERS) {
            throw new TeamException(TeamErrorCode.TEAM_MEMBER_LIMIT_EXCEEDED);
        }
    }

    private void validateDisplayNameAvailable(Long teamId, String displayName) {
        if (teamMemberRepository.existsByTeamIdAndDisplayName(teamId, displayName)) {
            throw new TeamException(TeamErrorCode.DISPLAY_NAME_DUPLICATED);
        }
    }
}
