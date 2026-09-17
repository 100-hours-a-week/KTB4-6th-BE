package com.backend.meety.domain.team.service;

import com.backend.meety.domain.team.dto.TeamCreateRequest;
import com.backend.meety.domain.team.dto.TeamCreateResponse;
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
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TeamService {

    private static final int MAX_INVITATION_CODE_ATTEMPTS = 5;

    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final TeamInvitationCodeRepository teamInvitationCodeRepository;
    private final UserRepository userRepository;

    @Transactional
    public TeamCreateResponse create(Long userId, TeamCreateRequest request) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new TeamException(TeamErrorCode.USER_NOT_FOUND));
        validateNoActiveTeam(userId);
        try {
            Team team = teamRepository.save(Team.create(request.name()));
            TeamMember leader = teamMemberRepository.save(
                    TeamMember.createLeader(user, team, request.displayName()));
            TeamInvitationCode invitationCode = teamInvitationCodeRepository.save(
                    TeamInvitationCode.create(team, generateUniqueCode()));
            return TeamCreateResponse.of(team, leader, invitationCode);
        } catch (DataAccessException e) {
            throw new TeamException(TeamErrorCode.TEAM_CREATE_FAILED);
        }
    }

    private void validateNoActiveTeam(Long userId) {
        if (teamMemberRepository.existsByUserIdAndMembershipStatus(userId, MembershipStatus.ACTIVE)) {
            throw new TeamException(TeamErrorCode.ACTIVE_TEAM_ALREADY_EXISTS);
        }
    }

    private String generateUniqueCode() {
        for (int attempt = 0; attempt < MAX_INVITATION_CODE_ATTEMPTS; attempt++) {
            String code = InvitationCodeGenerator.generate();
            if (!teamInvitationCodeRepository.existsByCode(code)) {
                return code;
            }
        }
        throw new TeamException(TeamErrorCode.TEAM_CREATE_FAILED);
    }
}
