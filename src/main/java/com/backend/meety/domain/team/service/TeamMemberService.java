package com.backend.meety.domain.team.service;

import com.backend.meety.domain.team.dto.MyTeamResponse;
import com.backend.meety.domain.team.dto.TeamJoinRequest;
import com.backend.meety.domain.team.dto.TeamMemberListResponse;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamBlock;
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
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
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
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MyTeamResponse join(Long userId, TeamJoinRequest request) {
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
            teamMemberRepository.save(TeamMember.createMember(user, team, request.displayName()));
            return MyTeamResponse.of(team.getId());
        } catch (DataAccessException e) {
            throw new TeamException(TeamErrorCode.TEAM_MEMBERSHIP_CREATE_FAILED);
        }
    }

    @Transactional(readOnly = true)
    public TeamMemberListResponse getMembers(Long userId, Long teamId) {
        validateActiveMembership(teamId, userId);
        try {
            List<TeamMember> members = teamMemberRepository
                    .findAllActiveOrderByLeaderFirst(teamId, MembershipStatus.ACTIVE);
            return TeamMemberListResponse.from(members);
        } catch (DataAccessException e) {
            throw new TeamException(TeamErrorCode.TEAM_MEMBER_LOOKUP_FAILED);
        }
    }

    @Transactional
    public void leave(Long userId, Long teamId) {
        Team team = teamRepository.findByIdForUpdate(teamId)
                .orElseThrow(() -> new TeamException(TeamErrorCode.TEAM_NOT_FOUND));
        if (team.isDeleted()) {
            throw new TeamException(TeamErrorCode.TEAM_NOT_FOUND);
        }
        TeamMember teamMember = teamMemberRepository
                .findByTeamIdAndUserIdAndMembershipStatus(teamId, userId, MembershipStatus.ACTIVE)
                .orElseThrow(() -> new TeamException(TeamErrorCode.TEAM_MEMBERSHIP_REQUIRED));
        if (teamMember.isLeader()) {
            throw new TeamException(TeamErrorCode.LEADER_CANNOT_LEAVE_TEAM);
        }
        teamMember.leave(LocalDateTime.now(clock));
        // TODO: 명시적 flush는 커밋 시점 UPDATE 실패를 TEAM_LEAVE_FAILED로 번역하기 위한 장치.
        //  이 에러 코드를 유지할지(= flush 유지) 공통 500으로 갈지(= flush 제거) 팀 논의 필요
        try {
            teamMemberRepository.flush();
        } catch (DataAccessException e) {
            throw new TeamException(TeamErrorCode.TEAM_LEAVE_FAILED);
        }
    }

    @Transactional
    public void kick(Long userId, Long teamId, Long teamMemberId) {
        Team team = teamRepository.findByIdForUpdate(teamId)
                .orElseThrow(() -> new TeamException(TeamErrorCode.TEAM_NOT_FOUND));
        if (team.isDeleted()) {
            throw new TeamException(TeamErrorCode.TEAM_NOT_FOUND);
        }
        validateLeader(teamId, userId);
        TeamMember target = teamMemberRepository.findById(teamMemberId)
                .filter(member -> member.getTeam().getId().equals(teamId))
                .orElseThrow(() -> new TeamException(TeamErrorCode.TEAM_MEMBER_NOT_FOUND));
        if (target.getUser().getId().equals(userId)) {
            throw new TeamException(TeamErrorCode.CANNOT_KICK_SELF);
        }
        if (target.getMembershipStatus() != MembershipStatus.ACTIVE) {
            throw new TeamException(TeamErrorCode.TEAM_MEMBER_NOT_ACTIVE);
        }
        target.kick(LocalDateTime.now(clock));
        // TODO: 명시적 flush는 커밋 시점 UPDATE 실패를 TEAM_MEMBER_KICK_FAILED로 번역하기 위한 장치 (leave와 동일 논의 대상)
        try {
            teamBlockRepository.save(TeamBlock.create(team, target.getUser()));
            teamMemberRepository.flush();
        } catch (DataAccessException e) {
            throw new TeamException(TeamErrorCode.TEAM_MEMBER_KICK_FAILED);
        }
    }

    private void validateLeader(Long teamId, Long userId) {
        TeamMember requester = teamMemberRepository
                .findByTeamIdAndUserIdAndMembershipStatus(teamId, userId, MembershipStatus.ACTIVE)
                .orElseThrow(() -> new TeamException(TeamErrorCode.TEAM_LEADER_REQUIRED));
        if (!requester.isLeader()) {
            throw new TeamException(TeamErrorCode.TEAM_LEADER_REQUIRED);
        }
    }

    private void validateActiveMembership(Long teamId, Long userId) {
        teamRepository.findByIdAndDeletedAtIsNull(teamId)
                .orElseThrow(() -> new TeamException(TeamErrorCode.TEAM_NOT_FOUND));
        teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(teamId, userId, MembershipStatus.ACTIVE)
                .orElseThrow(() -> new TeamException(TeamErrorCode.TEAM_MEMBERSHIP_REQUIRED));
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
