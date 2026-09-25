package com.backend.meety.domain.meeting.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class MeetingParticipantRepositoryTest {

    @Test
    @DisplayName("현재 참석자 목록 조회는 JOINED와 deletedAt 조건을 사용하고 TeamMember를 fetch join한다")
    void findCurrentParticipantsQuery() throws NoSuchMethodException {
        // 테스트 목적:
        // 현재 접속 중인 참석자 조회가 JOINED 상태와 deletedAt 조건을 사용하고
        // 응답 생성 시 TeamMember 추가 조회가 반복되지 않도록 fetch join하는지 검증한다.

        // given
        Query query = MeetingParticipantRepository.class
                .getMethod("findCurrentParticipantsByMeetingId", Long.class)
                .getAnnotation(Query.class);

        // when
        String jpql = query.value();

        // then
        assertThat(query).isNotNull();
        assertThat(jpql).contains("join fetch p.teamMember");
        assertThat(jpql).contains("p.meeting.id = :meetingId");
        assertThat(jpql).contains("ParticipationStatus.JOINED");
        assertThat(jpql).contains("p.deletedAt is null");
    }

    @Test
    @DisplayName("회의 참석 이력 조회는 TeamMember를 fetch join하고 상태로 제외하지 않는다")
    void findAllByMeetingIdWithTeamMemberFetchesTeamMemberWithoutStatusFilter() throws NoSuchMethodException {
        // 테스트 목적:
        // 발화자 매핑 후보 조회가 현재 ACTIVE 멤버가 아니라 회의 참석 이력을 기준으로 하며
        // 후보 nickname 접근 시 TeamMember 추가 조회가 반복되지 않도록 fetch join하는지 검증한다.

        // given
        Query query = MeetingParticipantRepository.class
                .getMethod("findAllByMeetingIdWithTeamMember", Long.class)
                .getAnnotation(Query.class);

        // when
        String jpql = query.value();

        // then
        assertThat(query).isNotNull();
        assertThat(jpql).contains("join fetch p.teamMember");
        assertThat(jpql).contains("p.meeting.id = :meetingId");
        assertThat(jpql).doesNotContain("ParticipationStatus.JOINED");
        assertThat(jpql).doesNotContain("p.deletedAt is null");
        assertThat(jpql).contains("order by p.createdAt asc, p.id asc");
    }
}
