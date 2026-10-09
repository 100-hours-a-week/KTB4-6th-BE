package com.backend.meety.domain.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

class NotificationRepositoryTest {

    @Test
    @DisplayName("알림 목록 조회는 사용자와 현재 팀, 삭제 조건, cursor 조건, 최신순 정렬을 사용한다")
    void findPageByUserIdAndTeamIdQuery() throws NoSuchMethodException {
        // 테스트 목적:
        // 알림 목록 조회가 본인, 현재 ACTIVE 팀, 삭제되지 않은 알림만 대상으로 하고
        // notificationId 기반 keyset pagination과 id desc 정렬을 사용하는지 검증한다.

        // given
        Query query = NotificationRepository.class
                .getMethod("findPageByUserIdAndTeamId", Long.class, Long.class, Long.class, Pageable.class)
                .getAnnotation(Query.class);

        // when
        String jpql = query.value();

        // then
        assertThat(query).isNotNull();
        assertThat(jpql).contains("n.user.id = :userId");
        assertThat(jpql).contains("n.team.id = :teamId");
        assertThat(jpql).contains("n.deletedAt is null");
        assertThat(jpql).contains("n.id < :cursor");
        assertThat(jpql).contains("order by n.id desc");
        assertThat(NotificationRepository.class
                .getMethod("findPageByUserIdAndTeamId", Long.class, Long.class, Long.class, Pageable.class)
                .getReturnType()).isEqualTo(List.class);
    }

    @Test
    @DisplayName("삭제된 알림은 목록 조회에서 제외된다")
    void findPageExcludesDeletedNotifications() throws NoSuchMethodException {
        // 테스트 목적:
        // soft delete된 알림이 알림 목록 응답에 포함되지 않도록
        // 목록 조회 쿼리에 deletedAt IS NULL 조건이 있는지 검증한다.

        // given
        Query query = NotificationRepository.class
                .getMethod("findPageByUserIdAndTeamId", Long.class, Long.class, Long.class, Pageable.class)
                .getAnnotation(Query.class);

        // when
        String jpql = query.value();

        // then
        assertThat(jpql).contains("n.deletedAt is null");
    }

    @Test
    @DisplayName("미읽음 수 조회는 사용자와 현재 팀, 삭제 조건, 미읽음 조건을 사용한다")
    void countUnreadByUserIdAndTeamIdQuery() throws NoSuchMethodException {
        // 테스트 목적:
        // unreadCount가 현재 사용자와 현재 ACTIVE 팀의 삭제되지 않은 미읽음 알림만
        // 집계하도록 쿼리 조건을 사용하는지 검증한다.

        // given
        Query query = NotificationRepository.class
                .getMethod("countUnreadByUserIdAndTeamId", Long.class, Long.class)
                .getAnnotation(Query.class);

        // when
        String jpql = query.value();

        // then
        assertThat(query).isNotNull();
        assertThat(jpql).contains("n.user.id = :userId");
        assertThat(jpql).contains("n.team.id = :teamId");
        assertThat(jpql).contains("n.deletedAt is null");
        assertThat(jpql).contains("n.isRead = false");
    }

    @Test
    @DisplayName("삭제된 미읽음 알림은 unreadCount에서 제외된다")
    void countUnreadExcludesDeletedUnreadNotifications() throws NoSuchMethodException {
        // 테스트 목적:
        // 읽지 않은 알림이라도 soft delete된 경우
        // unreadCount 집계 대상에서 제외되는지 검증한다.

        // given
        Query query = NotificationRepository.class
                .getMethod("countUnreadByUserIdAndTeamId", Long.class, Long.class)
                .getAnnotation(Query.class);

        // when
        String jpql = query.value();

        // then
        assertThat(jpql).contains("n.deletedAt is null");
        assertThat(jpql).contains("n.isRead = false");
    }

    @Test
    @DisplayName("개별 알림 조회는 사용자와 현재 팀, 삭제 조건을 메서드명에 포함한다")
    void findByIdAndUserIdAndTeamIdAndDeletedAtIsNullMethod() throws NoSuchMethodException {
        // 테스트 목적:
        // 개별 알림 읽음 처리가 타 사용자 또는 다른 팀 알림을 처리하지 않도록
        // 조회 메서드가 알림 ID, 사용자 ID, 팀 ID, 삭제 조건을 모두 포함하는지 검증한다.

        // when, then
        assertThat(NotificationRepository.class
                .getMethod("findByIdAndUserIdAndTeamIdAndDeletedAtIsNull", Long.class, Long.class, Long.class)
                .getReturnType()).isEqualTo(Optional.class);
    }

    @Test
    @DisplayName("모두 읽음 처리는 호출 시점의 마지막 알림 ID 이하 미읽음만 갱신한다")
    void markUnreadAsReadUntilIdQuery() throws NoSuchMethodException {
        // 테스트 목적:
        // 모두 읽음 처리가 현재 사용자와 현재 ACTIVE 팀의 삭제되지 않은 미읽음 알림 중
        // 호출 시점에 확인한 마지막 알림 ID 이하만 읽음 처리하는지 검증한다.

        // given
        Query query = NotificationRepository.class
                .getMethod("markUnreadAsReadUntilId", Long.class, Long.class, Long.class)
                .getAnnotation(Query.class);
        Modifying modifying = NotificationRepository.class
                .getMethod("markUnreadAsReadUntilId", Long.class, Long.class, Long.class)
                .getAnnotation(Modifying.class);

        // when
        String jpql = query.value();

        // then
        assertThat(query).isNotNull();
        assertThat(modifying).isNotNull();
        assertThat(jpql).contains("set n.isRead = true");
        assertThat(jpql).contains("n.user.id = :userId");
        assertThat(jpql).contains("n.team.id = :teamId");
        assertThat(jpql).contains("n.deletedAt is null");
        assertThat(jpql).contains("n.isRead = false");
        assertThat(jpql).contains("n.id <= :maxId");
    }

    @Test
    @DisplayName("호출 시점 경계 조회는 사용자와 현재 팀, 삭제 조건의 최대 알림 ID를 조회한다")
    void findMaxIdByUserIdAndTeamIdQuery() throws NoSuchMethodException {
        // 테스트 목적:
        // 모두 읽음 처리에서 요청 중 새로 생성되는 알림까지 읽음 처리하지 않도록
        // 현재 조건의 최대 알림 ID를 먼저 조회하는지 검증한다.

        // given
        Query query = NotificationRepository.class
                .getMethod("findMaxIdByUserIdAndTeamId", Long.class, Long.class)
                .getAnnotation(Query.class);

        // when
        String jpql = query.value();

        // then
        assertThat(query).isNotNull();
        assertThat(jpql).contains("select max(n.id)");
        assertThat(jpql).contains("n.user.id = :userId");
        assertThat(jpql).contains("n.team.id = :teamId");
        assertThat(jpql).contains("n.deletedAt is null");
    }
}
