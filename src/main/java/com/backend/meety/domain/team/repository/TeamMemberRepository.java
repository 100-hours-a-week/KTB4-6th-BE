package com.backend.meety.domain.team.repository;

import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.TeamMember;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamMemberRepository extends JpaRepository<TeamMember, Long> {

    Optional<TeamMember> findByTeamIdAndUserIdAndMembershipStatus(
            Long teamId,
            Long userId,
            MembershipStatus membershipStatus
    );

    boolean existsByTeamIdAndUserIdAndMembershipStatus(
            Long teamId,
            Long userId,
            MembershipStatus membershipStatus
    );

    Optional<TeamMember> findByUserIdAndMembershipStatus(Long userId, MembershipStatus membershipStatus);

    boolean existsByUserIdAndMembershipStatus(Long userId, MembershipStatus membershipStatus);

    long countByTeamIdAndMembershipStatus(Long teamId, MembershipStatus membershipStatus);

    boolean existsByTeamIdAndDisplayName(Long teamId, String displayName);
}
