package com.backend.meety.domain.recording.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class RecordingSessionRepositoryTest {

    @Test
    @DisplayName("알림용 녹음 세션 조회는 회의 팀과 시작자를 fetch join한다")
    void findByIdWithMeetingTeamAndStarterQuery() throws NoSuchMethodException {
        // 테스트 목적:
        // 녹음 시작 알림 생성 시 실제 시작자 displayName과 팀 식별자를 사용할 수 있도록
        // RecordingSession 조회가 Meeting, Team, startedByTeamMember를 함께 가져오는지 검증한다.

        // given
        Query query = RecordingSessionRepository.class
                .getMethod("findByIdWithMeetingTeamAndStarter", Long.class)
                .getAnnotation(Query.class);

        // when
        String jpql = query.value();

        // then
        assertThat(query).isNotNull();
        assertThat(jpql).contains("join fetch r.meeting m");
        assertThat(jpql).contains("join fetch m.team");
        assertThat(jpql).contains("join fetch r.startedByTeamMember");
        assertThat(jpql).contains("r.id = :sessionId");
        assertThat(jpql).contains("r.deletedAt is null");
        assertThat(RecordingSessionRepository.class
                .getMethod("findByIdWithMeetingTeamAndStarter", Long.class)
                .getReturnType()).isEqualTo(Optional.class);
    }
}
