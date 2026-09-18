package com.backend.meety.domain.team.service;

import com.backend.meety.domain.team.dto.InvitationCodeResponse;
import com.backend.meety.domain.team.dto.MyTeamResponse;
import com.backend.meety.domain.team.dto.TeamCreateRequest;
import com.backend.meety.domain.team.dto.TeamCreateResponse;
import com.backend.meety.domain.team.dto.TeamDetailResponse;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamInvitationCode;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.exception.TeamErrorCode;
import com.backend.meety.domain.team.exception.TeamException;
import com.backend.meety.domain.team.repository.TeamInvitationCodeRepository;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.domain.team.repository.TeamRepository;
import com.backend.meety.domain.team.util.InvitationCodeGenerator;
import com.backend.meety.domain.user.entity.User;
import com.backend.meety.domain.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TeamService {

    private static final int MAX_INVITATION_CODE_ATTEMPTS = 5;
    private static final ZoneId KST_ZONE_ID = ZoneId.of("Asia/Seoul");
    private static final List<MembershipStatus> TEAM_DEPARTURE_STATUSES =
            List.of(MembershipStatus.LEFT, MembershipStatus.KICKED, MembershipStatus.TEAM_DELETED);

    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final TeamInvitationCodeRepository teamInvitationCodeRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    @Transactional
    public TeamCreateResponse create(Long userId, TeamCreateRequest request) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new TeamException(TeamErrorCode.USER_NOT_FOUND));
        validateNoActiveTeam(userId);
        validateNoTeamLeftToday(userId);
        try {
            Team team = teamRepository.save(Team.create(request.name()));
            TeamMember leader = teamMemberRepository.save(
                    TeamMember.createLeader(user, team, request.displayName()));
            String code = generateUniqueCode()
                    .orElseThrow(() -> new TeamException(TeamErrorCode.TEAM_CREATE_FAILED));
            TeamInvitationCode invitationCode = teamInvitationCodeRepository.save(
                    TeamInvitationCode.create(team, code));
            return TeamCreateResponse.of(team, leader, invitationCode);
        } catch (DataAccessException e) {
            throw new TeamException(TeamErrorCode.TEAM_CREATE_FAILED);
        }
    }

    @Transactional
    public InvitationCodeResponse regenerateInvitationCode(Long userId, Long teamId) {
        Team team = teamRepository.findByIdForUpdate(teamId)
                .orElseThrow(() -> new TeamException(TeamErrorCode.TEAM_NOT_FOUND));
        if (team.isDeleted()) {
            throw new TeamException(TeamErrorCode.TEAM_NOT_FOUND);
        }
        validateLeader(teamId, userId);
        TeamInvitationCode currentCode = teamInvitationCodeRepository
                .findByTeamIdAndDeletedAtIsNull(teamId)
                .orElseThrow(() -> new IllegalStateException("활성 팀에 유효한 초대 코드가 없습니다."));
        validateDailyRegenerationLimit(currentCode);
        currentCode.revoke(LocalDateTime.now(clock));
        String code = generateUniqueCode()
                .orElseThrow(() -> new TeamException(TeamErrorCode.INVITATION_CODE_CREATE_FAILED));
        try {
            TeamInvitationCode newCode = teamInvitationCodeRepository.save(
                    TeamInvitationCode.create(team, code));
            return InvitationCodeResponse.from(newCode);
        } catch (DataAccessException e) {
            throw new TeamException(TeamErrorCode.INVITATION_CODE_CREATE_FAILED);
        }
    }

    private void validateLeader(Long teamId, Long userId) {
        TeamMember teamMember = teamMemberRepository
                .findByTeamIdAndUserIdAndMembershipStatus(teamId, userId, MembershipStatus.ACTIVE)
                .orElseThrow(() -> new TeamException(TeamErrorCode.TEAM_LEADER_REQUIRED));
        if (!teamMember.isLeader()) {
            throw new TeamException(TeamErrorCode.TEAM_LEADER_REQUIRED);
        }
    }

    private void validateDailyRegenerationLimit(TeamInvitationCode currentCode) {
        LocalDate today = LocalDate.now(clock.withZone(KST_ZONE_ID));
        if (currentCode.isCreatedOn(today)) {
            throw new TeamException(TeamErrorCode.INVITATION_CODE_REGENERATION_LIMIT_EXCEEDED);
        }
    }

    @Transactional(readOnly = true)
    public MyTeamResponse getMyTeam(Long userId) {
        return teamMemberRepository.findByUserIdAndMembershipStatus(userId, MembershipStatus.ACTIVE)
                .map(teamMember -> MyTeamResponse.of(teamMember.getTeam().getId()))
                .orElseGet(MyTeamResponse::noTeam);
    }

    @Transactional(readOnly = true)
    public TeamDetailResponse getTeam(Long userId, Long teamId) {
        Team team = teamRepository.findByIdAndDeletedAtIsNull(teamId)
                .orElseThrow(() -> new TeamException(TeamErrorCode.TEAM_NOT_FOUND));
        TeamMember teamMember = teamMemberRepository
                .findByTeamIdAndUserIdAndMembershipStatus(teamId, userId, MembershipStatus.ACTIVE)
                .orElseThrow(() -> new TeamException(TeamErrorCode.TEAM_ACCESS_DENIED));
        TeamInvitationCode invitationCode = teamInvitationCodeRepository
                .findByTeamIdAndDeletedAtIsNull(teamId)
                .orElseThrow(() -> new IllegalStateException("활성 팀에 유효한 초대 코드가 없습니다."));
        return TeamDetailResponse.of(team, teamMember, invitationCode.getCode());
    }

    private void validateNoActiveTeam(Long userId) {
        if (teamMemberRepository.existsByUserIdAndMembershipStatus(userId, MembershipStatus.ACTIVE)) {
            throw new TeamException(TeamErrorCode.ACTIVE_TEAM_ALREADY_EXISTS);
        }
    }

    private void validateNoTeamLeftToday(Long userId) {
        LocalDateTime startOfToday = LocalDate.now(clock.withZone(KST_ZONE_ID)).atStartOfDay();
        if (teamMemberRepository.existsByUserIdAndMembershipStatusInAndDeletedAtGreaterThanEqual(
                userId, TEAM_DEPARTURE_STATUSES, startOfToday)) {
            throw new TeamException(TeamErrorCode.TEAM_CREATE_DAILY_LIMIT_EXCEEDED);
        }
    }

    private Optional<String> generateUniqueCode() {
        for (int attempt = 0; attempt < MAX_INVITATION_CODE_ATTEMPTS; attempt++) {
            String code = InvitationCodeGenerator.generate();
            if (!teamInvitationCodeRepository.existsByCode(code)) {
                return Optional.of(code);
            }
        }
        return Optional.empty();
    }
}
