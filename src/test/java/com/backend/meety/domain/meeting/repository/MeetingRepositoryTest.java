package com.backend.meety.domain.meeting.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;
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

    @Test
    @DisplayName("수정/삭제용 회의 조회는 PESSIMISTIC_WRITE lock과 soft delete 조건을 사용한다")
    void findByIdForUpdateAndDeletedAtIsNullUsesPessimisticWriteLock() throws NoSuchMethodException {
        Lock lock = MeetingRepository.class
                .getMethod("findByIdForUpdateAndDeletedAtIsNull", Long.class)
                .getAnnotation(Lock.class);
        Query query = MeetingRepository.class
                .getMethod("findByIdForUpdateAndDeletedAtIsNull", Long.class)
                .getAnnotation(Query.class);

        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(query).isNotNull();
        assertThat(query.value()).contains("join fetch m.team");
        assertThat(query.value()).contains("join fetch m.createdByTeamMember");
        assertThat(query.value()).contains("m.deletedAt is null");
    }
}
