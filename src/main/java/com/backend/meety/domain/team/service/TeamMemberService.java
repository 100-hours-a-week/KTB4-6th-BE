package com.backend.meety.domain.team.service;

import com.backend.meety.domain.team.dto.MyTeamResponse;
import com.backend.meety.domain.team.dto.TeamJoinRequest;
import com.backend.meety.domain.team.dto.TeamBlockItemResponse;
import com.backend.meety.domain.team.dto.TeamBlockListResponse;
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
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
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
        String code = request.invitationCode().toUpperCase(Locale.ROOT);
        Team team = resolveTeamByCode(code);
        validateCodeStillActive(code, team.getId());
        validateNotBlocked(team.getId(), userId);
        validateCapacity(team.getId());
        validateDisplayNameAvailable(team.getId(), userId, request.displayName());
        try {
            teamMemberRepository.findByTeamIdAndUserId(team.getId(), userId)
                    .ifPresentOrElse(
                            existing -> existing.rejoin(request.displayName()),
                            () -> teamMemberRepository.save(
                                    TeamMember.createMember(user, team, request.displayName())));
            teamMemberRepository.flush();
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
        lockActiveTeam(teamId);
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
        Team team = lockActiveTeam(teamId);
        requireLeader(teamId, userId);
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

    @Transactional
    public void delegateLeader(Long userId, Long teamId, Long targetTeamMemberId) {
        lockActiveTeam(teamId);
        TeamMember leader = requireLeader(teamId, userId);
        TeamMember target = teamMemberRepository.findById(targetTeamMemberId)
                .filter(member -> member.getTeam().getId().equals(teamId))
                .orElseThrow(() -> new TeamException(TeamErrorCode.TEAM_MEMBER_NOT_FOUND));
        if (target.getId().equals(leader.getId())) {
            throw new TeamException(TeamErrorCode.LEADER_ALREADY_ASSIGNED);
        }
        if (target.getMembershipStatus() != MembershipStatus.ACTIVE) {
            throw new TeamException(TeamErrorCode.TARGET_TEAM_MEMBER_NOT_ACTIVE);
        }
        leader.demoteToMember();
        target.promoteToLeader();
        try {
            teamMemberRepository.flush();
        } catch (DataAccessException e) {
            throw new TeamException(TeamErrorCode.TEAM_LEADER_UPDATE_FAILED);
        }
    }

    private Team lockActiveTeam(Long teamId) {
        Team team = teamRepository.findByIdForUpdate(teamId)
                .orElseThrow(() -> new TeamException(TeamErrorCode.TEAM_NOT_FOUND));
        if (team.isDeleted()) {
            throw new TeamException(TeamErrorCode.TEAM_NOT_FOUND);
        }
        return team;
    }

    private TeamMember requireLeader(Long teamId, Long userId) {
        TeamMember requester = teamMemberRepository
                .findByTeamIdAndUserIdAndMembershipStatus(teamId, userId, MembershipStatus.ACTIVE)
                .orElseThrow(() -> new TeamException(TeamErrorCode.TEAM_LEADER_REQUIRED));
        if (!requester.isLeader()) {
            throw new TeamException(TeamErrorCode.TEAM_LEADER_REQUIRED);
        }
        return requester;
    }

    @Transactional(readOnly = true)
    public TeamBlockListResponse getBlocks(Long userId, Long teamId) {
        teamRepository.findByIdAndDeletedAtIsNull(teamId)
                .orElseThrow(() -> new TeamException(TeamErrorCode.TEAM_NOT_FOUND));
        requireLeader(teamId, userId);
        try {
            List<TeamBlock> blocks = teamBlockRepository.findAllByTeamIdAndDeletedAtIsNullOrderByIdAsc(teamId);
            Map<Long, String> displayNames = findLatestDisplayNames(teamId, blocks);
            return new TeamBlockListResponse(blocks.stream()
                    .map(block -> TeamBlockItemResponse.of(block, resolveDisplayName(displayNames, block)))
                    .toList());
        } catch (DataAccessException e) {
            throw new TeamException(TeamErrorCode.TEAM_BLOCK_LIST_FAILED);
        }
    }

    @Transactional
    public void unblock(Long userId, Long teamId, Long blockId) {
        lockActiveTeam(teamId);
        requireLeader(teamId, userId);
        TeamBlock block = teamBlockRepository.findById(blockId)
                .filter(found -> found.getTeam().getId().equals(teamId))
                .filter(TeamBlock::isActive)
                .orElseThrow(() -> new TeamException(TeamErrorCode.TEAM_BLOCK_NOT_FOUND));
        block.release(LocalDateTime.now(clock));
        teamMemberRepository.findByTeamIdAndUserId(teamId, block.getUser().getId())
                .filter(member -> member.getMembershipStatus() == MembershipStatus.KICKED)
                .ifPresent(TeamMember::releaseKick);
        try {
            teamBlockRepository.flush();
        } catch (DataAccessException e) {
            throw new TeamException(TeamErrorCode.TEAM_BLOCK_RELEASE_FAILED);
        }
    }

    private Map<Long, String> findLatestDisplayNames(Long teamId, List<TeamBlock> blocks) {
        List<Long> userIds = blocks.stream()
                .map(block -> block.getUser().getId())
                .toList();
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return teamMemberRepository.findAllByTeamIdAndUserIdIn(teamId, userIds).stream()
                .collect(Collectors.toMap(
                        member -> member.getUser().getId(),
                        Function.identity(),
                        (first, second) -> first.getId() > second.getId() ? first : second))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getDisplayName()));
    }

    private String resolveDisplayName(Map<Long, String> displayNames, TeamBlock block) {
        String displayName = displayNames.get(block.getUser().getId());
        if (displayName == null) {
            throw new IllegalStateException("차단된 사용자의 팀 멤버십 이력이 없습니다.");
        }
        return displayName;
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

    private void validateDisplayNameAvailable(Long teamId, Long userId, String displayName) {
        if (teamMemberRepository.existsByTeamIdAndDisplayNameAndUserIdNot(teamId, displayName, userId)) {
            throw new TeamException(TeamErrorCode.DISPLAY_NAME_DUPLICATED);
        }
    }
}
