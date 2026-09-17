package com.backend.meety.domain.team.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.meety.domain.team.entity.MembershipStatus;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class TeamMemberRepositoryTest {

    @Test
    @DisplayName("홈 ACTIVE 팀원 조회는 Team을 fetch join하고 단건 Optional을 반환한다")
    void findByUserIdAndMembershipStatusWithTeamQuery() throws NoSuchMethodException {
        Query query = TeamMemberRepository.class
                .getMethod("findByUserIdAndMembershipStatusWithTeam", Long.class, MembershipStatus.class)
                .getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.value()).contains("join fetch tm.team");
        assertThat(query.value()).contains("tm.user.id = :userId");
        assertThat(query.value()).contains("tm.membershipStatus = :membershipStatus");
        assertThat(TeamMemberRepository.class
                .getMethod("findByUserIdAndMembershipStatusWithTeam", Long.class, MembershipStatus.class)
                .getReturnType()).isEqualTo(Optional.class);
    }
}
