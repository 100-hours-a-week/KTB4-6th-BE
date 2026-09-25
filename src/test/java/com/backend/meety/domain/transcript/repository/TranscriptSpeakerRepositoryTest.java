package com.backend.meety.domain.transcript.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class TranscriptSpeakerRepositoryTest {

    @Test
    @DisplayName("발화자 목록 조회 쿼리는 회의 필터와 soft delete 조건을 사용한다")
    void findAllByMeetingIdOrderByIdUsesMeetingFilterAndSoftDeleteCondition() throws NoSuchMethodException {
        // 테스트 목적:
        // 발화자 목록 조회 repository query가 회의 ID와 soft delete 조건으로
        // 현재 회의의 유효한 발화자만 조회하는지 검증한다.

        // given
        Query query = TranscriptSpeakerRepository.class
                .getMethod("findAllByMeetingIdOrderById", Long.class)
                .getAnnotation(Query.class);

        // when
        String jpql = query.value();

        // then
        assertThat(query).isNotNull();
        assertThat(jpql).contains("ts.meeting.id = :meetingId");
        assertThat(jpql).contains("ts.deletedAt is null");
    }

    @Test
    @DisplayName("발화자 목록 조회 쿼리는 생성 순서 기준으로 정렬한다")
    void findAllByMeetingIdOrderByIdUsesIdAscendingOrder() throws NoSuchMethodException {
        // 테스트 목적:
        // 별도 발화자 정렬 정책이 없는 현재 요구사항에서
        // 발화자 목록이 생성 순서인 id ASC 기준으로 정렬되는지 검증한다.

        // given
        Query query = TranscriptSpeakerRepository.class
                .getMethod("findAllByMeetingIdOrderById", Long.class)
                .getAnnotation(Query.class);

        // when
        String jpql = query.value();

        // then
        assertThat(query).isNotNull();
        assertThat(jpql).contains("order by ts.id asc");
    }

    @Test
    @DisplayName("발화자 목록 조회 쿼리는 불필요한 연관 Entity fetch join을 사용하지 않는다")
    void findAllByMeetingIdOrderByIdDoesNotFetchJoinMappedTeamMember() throws NoSuchMethodException {
        // 테스트 목적:
        // 발화자 목록 응답에는 mappedTeamMemberId만 필요하므로
        // TeamMember 전체 조회를 위한 fetch join을 사용하지 않는지 검증한다.

        // given
        Query query = TranscriptSpeakerRepository.class
                .getMethod("findAllByMeetingIdOrderById", Long.class)
                .getAnnotation(Query.class);

        // when
        String jpql = query.value();

        // then
        assertThat(query).isNotNull();
        assertThat(jpql).doesNotContain("join fetch");
    }
}
