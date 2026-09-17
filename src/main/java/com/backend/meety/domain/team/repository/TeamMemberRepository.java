package com.backend.meety.domain.team.repository;

import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.TeamMember;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    @Query("""
            select tm
            from TeamMember tm
            join fetch tm.team
            where tm.user.id = :userId
              and tm.membershipStatus = :membershipStatus
            """)
    Optional<TeamMember> findByUserIdAndMembershipStatusWithTeam(
            @Param("userId") Long userId,
            @Param("membershipStatus") MembershipStatus membershipStatus
    );

    long countByTeamIdAndMembershipStatus(Long teamId, MembershipStatus membershipStatus);
}
