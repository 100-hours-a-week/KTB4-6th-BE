package com.backend.meety.domain.meeting.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingStatus;
import com.backend.meety.domain.meeting.repository.support.MeetingCursor;
import com.backend.meety.domain.meeting.repository.support.MeetingListSearchCondition;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MeetingRepositoryImplTest {

    @Test
    @DisplayName("목록 조회 JPQL은 soft deleted 회의를 제외하고 effectiveStartAt 정렬을 사용한다")
    void findMeetingsByConditionUsesEffectiveStartAtOrder() {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Meeting> query = queryMock(entityManager);
        MeetingRepositoryImpl repository = new MeetingRepositoryImpl(entityManager);

        repository.findMeetingsByCondition(new MeetingListSearchCondition(
                1L,
                "스프린트",
                LocalDateTime.of(2026, 9, 15, 0, 0),
                LocalDateTime.of(2026, 9, 16, 0, 0),
                null,
                21
        ));

        ArgumentCaptor<String> jpqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createQuery(jpqlCaptor.capture(), eq(Meeting.class));
        String jpql = jpqlCaptor.getValue();
        assertThat(jpql).contains("m.deletedAt is null");
        assertThat(jpql).contains("m.title like :keyword");
        assertThat(jpql).contains("when m.status = :waitingStatus then m.scheduledAt");
        assertThat(jpql).contains("else m.startedAt");
        assertThat(jpql).contains("function('date'");
        assertThat(jpql).contains("desc");
        assertThat(jpql).contains("asc");
        assertThat(jpql).contains("m.id asc");
        verify(query).setMaxResults(21);
        verify(query).setParameter("waitingStatus", MeetingStatus.WAITING);
    }

    @Test
    @DisplayName("cursor가 있으면 정렬과 일치하는 다음 페이지 조건을 사용한다")
    void findMeetingsByConditionUsesCursorCondition() {
        EntityManager entityManager = mock(EntityManager.class);
        queryMock(entityManager);
        MeetingRepositoryImpl repository = new MeetingRepositoryImpl(entityManager);

        repository.findMeetingsByCondition(new MeetingListSearchCondition(
                1L,
                null,
                null,
                null,
                new MeetingCursor(LocalDateTime.of(2026, 9, 15, 14, 0), 123L),
                21
        ));

        ArgumentCaptor<String> jpqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createQuery(jpqlCaptor.capture(), eq(Meeting.class));
        String jpql = jpqlCaptor.getValue();
        assertThat(jpql).contains("< :cursorDateStart");
        assertThat(jpql).contains("> :cursorEffectiveStartAt");
        assertThat(jpql).contains("m.id > :cursorMeetingId");
    }

    @SuppressWarnings("unchecked")
    private TypedQuery<Meeting> queryMock(EntityManager entityManager) {
        TypedQuery<Meeting> query = mock(TypedQuery.class);
        when(entityManager.createQuery(any(String.class), eq(Meeting.class))).thenReturn(query);
        when(query.setParameter(any(String.class), any())).thenReturn(query);
        when(query.setMaxResults(any(Integer.class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        return query;
    }
}
