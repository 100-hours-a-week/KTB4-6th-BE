package com.backend.meety.domain.transcript.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class TranscriptSegmentRepositoryTest {

    @Test
    void findAllByMeetingIdOrderBySequenceUsesMeetingFilterAndSequenceOrder() throws NoSuchMethodException {
        Query query = TranscriptSegmentRepository.class
                .getMethod("findAllByMeetingIdOrderBySequence", Long.class)
                .getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.value()).contains("s.meeting.id = :meetingId");
        assertThat(query.value()).contains("s.deletedAt is null");
        assertThat(query.value()).contains("order by s.sequenceNumber asc, s.id asc");
        assertThat(query.value()).doesNotContain("lower(");
        assertThat(query.value()).doesNotContain("like");
    }
}
