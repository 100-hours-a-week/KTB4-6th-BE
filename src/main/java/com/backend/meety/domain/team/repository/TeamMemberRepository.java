package com.backend.meety.domain.team.repository;

import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.TeamMember;
import java.time.LocalDateTime;
import java.util.List;
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

    boolean existsByTeamIdAndDisplayName(Long teamId, String displayName);

    @Query("""
            select tm
            from TeamMember tm
            where tm.team.id = :teamId
              and tm.membershipStatus = :membershipStatus
              and tm.deletedAt is null
            order by case tm.role
                         when com.backend.meety.domain.team.entity.TeamMemberRole.LEADER then 0
                         else 1
                     end,
                     tm.id asc
            """)
    List<TeamMember> findAllActiveOrderByLeaderFirst(
            @Param("teamId") Long teamId,
            @Param("membershipStatus") MembershipStatus membershipStatus
    );

    boolean existsByUserIdAndMembershipStatusAndDeletedAtGreaterThanEqual(
            Long userId,
            MembershipStatus membershipStatus,
            LocalDateTime deletedAt
    );
}
