package com.backend.meety.domain.meeting.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class MeetingMetricRepositoryTest {

    @Test
    @DisplayName("발언 균형도 평균 query는 완료 회의와 soft delete 및 null score 제외 조건을 사용한다")
    void averageSpeechBalanceScoreByTeamIdQuery() throws NoSuchMethodException {
        Query query = MeetingMetricRepository.class
                .getMethod("averageSpeechBalanceScoreByTeamId", Long.class)
                .getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.nativeQuery()).isTrue();
        assertThat(query.value()).contains("avg(mm.speech_balance_score)");
        assertThat(query.value()).contains("join meetings m on m.id = mm.meeting_id");
        assertThat(query.value()).contains("m.team_id = :teamId");
        assertThat(query.value()).contains("m.status = 'COMPLETED'");
        assertThat(query.value()).contains("m.deleted_at is null");
        assertThat(query.value()).contains("mm.deleted_at is null");
        assertThat(query.value()).contains("mm.speech_balance_score is not null");
    }
}
