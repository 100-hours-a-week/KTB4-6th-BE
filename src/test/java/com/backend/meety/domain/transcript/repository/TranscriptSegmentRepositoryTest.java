package com.backend.meety.domain.transcript.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class TranscriptSegmentRepositoryTest {

    @Test
    @DisplayName("전체 전사 조회 쿼리는 회의 필터와 발화 순서 정렬을 사용한다")
    void findAllByMeetingIdOrderBySequenceUsesMeetingFilterAndSequenceOrder() throws NoSuchMethodException {
        // 테스트 목적:
        // 전체 전사 조회 repository query가 회의 ID와 soft delete 조건을 사용하고
        // 발화 순서 기준으로 정렬하는지 검증한다.

        // given
        Query query = TranscriptSegmentRepository.class
                .getMethod("findAllByMeetingIdOrderBySequence", Long.class)
                .getAnnotation(Query.class);

        // when
        String jpql = query.value();

        // then
        assertThat(query).isNotNull();
        assertThat(jpql).contains("s.meeting.id = :meetingId");
        assertThat(jpql).contains("s.deletedAt is null");
        assertThat(jpql).contains("order by s.sequenceNumber asc, s.id asc");
        assertThat(jpql).contains("left join fetch s.transcriptSpeaker speaker");
        assertThat(jpql).contains("left join fetch speaker.mappedTeamMember");
        assertThat(jpql).doesNotContain("lower(");
        assertThat(jpql).doesNotContain("like");
    }

    @Test
    @DisplayName("segment 기반 발화자 매핑 조회 쿼리는 발화자와 매핑 팀원을 fetch join한다")
    void findByIdAndMeetingIdWithSpeakerFetchesSpeakerAndMappedTeamMember() throws NoSuchMethodException {
        // 테스트 목적:
        // segment 기반 발화자 매핑 상세 조회에서 발화자와 매핑 팀원 접근 시
        // 추가 쿼리가 반복되지 않도록 fetch join을 사용하는지 검증한다.

        // given
        Query query = TranscriptSegmentRepository.class
                .getMethod("findByIdAndMeetingIdWithSpeaker", Long.class, Long.class)
                .getAnnotation(Query.class);

        // when
        String jpql = query.value();

        // then
        assertThat(query).isNotNull();
        assertThat(jpql).contains("s.id = :segmentId");
        assertThat(jpql).contains("s.meeting.id = :meetingId");
        assertThat(jpql).contains("s.deletedAt is null");
        assertThat(jpql).contains("left join fetch s.transcriptSpeaker speaker");
        assertThat(jpql).contains("left join fetch speaker.mappedTeamMember");
    }

    @Test
    @DisplayName("전사 검색 쿼리는 content 부분 일치와 발화 순서 정렬을 사용한다")
    void searchByMeetingIdAndContentUsesContentFilterAndSequenceOrder() throws NoSuchMethodException {
        // 테스트 목적:
        // 전사 검색 repository query가 회의 ID, soft delete, content 부분 일치 조건을 사용하고
        // 검색 결과를 발화 순서로 정렬하는지 검증한다.

        // given
        Query query = TranscriptSegmentRepository.class
                .getMethod("searchByMeetingIdAndContent", Long.class, String.class)
                .getAnnotation(Query.class);

        // when
        String jpql = query.value();

        // then
        assertThat(query).isNotNull();
        assertThat(jpql).contains("s.meeting.id = :meetingId");
        assertThat(jpql).contains("s.deletedAt is null");
        assertThat(jpql).contains("lower(s.content) like concat('%', lower(:keyword), '%')");
        assertThat(jpql).contains("order by s.sequenceNumber asc, s.id asc");
        assertThat(jpql).contains("left join fetch s.transcriptSpeaker speaker");
        assertThat(jpql).contains("left join fetch speaker.mappedTeamMember");
    }
}
