package com.backend.meety.domain.transcript.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class TranscriptSpeakerRepositoryTest {

    @Test
    @DisplayName("발화자 단건 조회 쿼리는 발화자 ID와 회의 ID를 함께 제한한다")
    void findByIdAndMeetingIdAndDeletedAtIsNullUsesSpeakerAndMeetingFilter() throws NoSuchMethodException {
        // 테스트 목적:
        // 발화자 매핑 수정 전 단건 조회가 transcriptSpeakerId만 보지 않고
        // 요청 meetingId의 발화자인지 함께 제한하는지 검증한다.

        // given
        Query query = TranscriptSpeakerRepository.class
                .getMethod("findByIdAndMeetingIdAndDeletedAtIsNull", Long.class, Long.class)
                .getAnnotation(Query.class);

        // when
        String jpql = query.value();

        // then
        assertThat(query).isNotNull();
        assertThat(jpql).contains("ts.id = :transcriptSpeakerId");
        assertThat(jpql).contains("ts.meeting.id = :meetingId");
    }

    @Test
    @DisplayName("발화자 단건 조회 쿼리는 soft delete 조건을 사용한다")
    void findByIdAndMeetingIdAndDeletedAtIsNullExcludesDeletedSpeaker() throws NoSuchMethodException {
        // 테스트 목적:
        // 삭제된 발화자는 매핑 수정 대상에서 제외되도록
        // 단건 조회 query가 soft delete 조건을 포함하는지 검증한다.

        // given
        Query query = TranscriptSpeakerRepository.class
                .getMethod("findByIdAndMeetingIdAndDeletedAtIsNull", Long.class, Long.class)
                .getAnnotation(Query.class);

        // when
        String jpql = query.value();

        // then
        assertThat(query).isNotNull();
        assertThat(jpql).contains("ts.deletedAt is null");
    }
}
