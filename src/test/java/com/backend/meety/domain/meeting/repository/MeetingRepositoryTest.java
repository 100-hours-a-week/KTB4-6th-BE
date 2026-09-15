package com.backend.meety.domain.meeting.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class MeetingRepositoryTest {

    @Test
    @DisplayName("당일 생성 수 조회는 status와 deletedAt 조건을 포함하지 않는다")
    void countCreatedTodayQueryDoesNotFilterStatusOrSoftDeletedMeeting() throws NoSuchMethodException {
        Query query = MeetingRepository.class
                .getMethod(
                        "countCreatedTodayByTeamId",
                        Long.class,
                        LocalDateTime.class,
                        LocalDateTime.class
                )
                .getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.value()).contains("count(m)");
        assertThat(query.value()).contains("m.team.id = :teamId");
        assertThat(query.value()).contains("m.createdAt >= :startOfDay");
        assertThat(query.value()).contains("m.createdAt < :nextDay");
        assertThat(query.value()).doesNotContain("status");
        assertThat(query.value()).doesNotContain("deletedAt");
        assertThat(query.value()).doesNotContain("deleted_at");
    }
}
