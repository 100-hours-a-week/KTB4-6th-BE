package com.backend.meety.domain.meeting.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class MeetingParticipantRepositoryTest {

    @Test
    @DisplayName("현재 참석자 목록 조회는 JOINED와 deletedAt 조건을 사용하고 TeamMember를 fetch join한다")
    void findCurrentParticipantsQuery() throws NoSuchMethodException {
        Query query = MeetingParticipantRepository.class
                .getMethod("findCurrentParticipantsByMeetingId", Long.class)
                .getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.value()).contains("join fetch p.teamMember");
        assertThat(query.value()).contains("p.meeting.id = :meetingId");
        assertThat(query.value()).contains("ParticipationStatus.JOINED");
        assertThat(query.value()).contains("p.deletedAt is null");
    }
}
