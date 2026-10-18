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

    @Test
    @DisplayName("알림 수신자 조회는 ACTIVE 팀원의 Team과 User를 fetch join한다")
    void findAllByTeamIdAndMembershipStatusWithUserAndTeamQuery() throws NoSuchMethodException {
        // 테스트 목적:
        // 알림 생성 시 팀의 ACTIVE 멤버 전체를 조회하면서
        // 사용자별 알림 생성 과정에서 Team/User 추가 조회가 반복되지 않도록 fetch join하는지 검증한다.

        // given
        Query query = TeamMemberRepository.class
                .getMethod("findAllByTeamIdAndMembershipStatusWithUserAndTeam", Long.class, MembershipStatus.class)
                .getAnnotation(Query.class);

        // when
        String jpql = query.value();

        // then
        assertThat(query).isNotNull();
        assertThat(jpql).contains("join fetch tm.team");
        assertThat(jpql).contains("join fetch tm.user");
        assertThat(jpql).contains("tm.team.id = :teamId");
        assertThat(jpql).contains("tm.membershipStatus = :membershipStatus");
        assertThat(jpql).contains("tm.deletedAt is null");
        assertThat(jpql).contains("order by tm.id asc");
    }
}
