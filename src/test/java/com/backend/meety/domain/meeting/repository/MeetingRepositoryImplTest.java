package com.backend.meety.domain.meeting.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingStatus;
import com.backend.meety.domain.meeting.repository.support.MeetingListSearchCondition;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MeetingRepositoryImplTest {

    @Test
    @DisplayName("날짜 그룹 조회 JPQL은 distinct effectiveDate와 cursor 조건을 사용한다")
    void findMeetingDatesByConditionUsesDistinctEffectiveDateAndCursor() {
        EntityManager entityManager = mock(EntityManager.class);
        Query query = queryMock(entityManager);
        MeetingRepositoryImpl repository = new MeetingRepositoryImpl(entityManager);

        List<LocalDate> dates = repository.findMeetingDatesByCondition(new MeetingListSearchCondition(
                1L,
                "스프린트",
                LocalDateTime.of(2026, 9, 1, 0, 0),
                LocalDateTime.of(2026, 10, 1, 0, 0),
                LocalDate.of(2026, 9, 13),
                6
        ));

        assertThat(dates).containsExactly(LocalDate.of(2026, 9, 17));
        ArgumentCaptor<String> jpqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createQuery(jpqlCaptor.capture());
        String jpql = jpqlCaptor.getValue();
        assertThat(jpql).contains("select distinct function('date'");
        assertThat(jpql).contains("m.deletedAt is null");
        assertThat(jpql).contains("m.title like :keyword");
        assertThat(jpql).contains("when m.status = :waitingStatus then m.scheduledAt");
        assertThat(jpql).contains("else m.startedAt");
        assertThat(jpql).contains(">= :fromInclusive");
        assertThat(jpql).contains("< :toExclusive");
        assertThat(jpql).contains("< :cursorDateStart");
        assertThat(jpql).contains("desc");
        verify(query).setMaxResults(6);
        verify(query).setParameter("waitingStatus", MeetingStatus.WAITING);
        verify(query).setParameter("cursorDateStart", LocalDate.of(2026, 9, 13).atStartOfDay());
    }

    @Test
    @DisplayName("선택 날짜 회의 조회 JPQL은 동일 필터와 선택 날짜 범위를 사용한다")
    void findMeetingsByDatesUsesSameFiltersAndSelectedDateRanges() {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Meeting> query = typedQueryMock(entityManager);
        MeetingRepositoryImpl repository = new MeetingRepositoryImpl(entityManager);

        repository.findMeetingsByDates(
                new MeetingListSearchCondition(
                        1L,
                        "스프린트",
                        LocalDateTime.of(2026, 9, 1, 0, 0),
                        LocalDateTime.of(2026, 10, 1, 0, 0),
                        LocalDate.of(2026, 9, 13),
                        6
                ),
                List.of(LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 16))
        );

        ArgumentCaptor<String> jpqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createQuery(jpqlCaptor.capture(), eq(Meeting.class));
        String jpql = jpqlCaptor.getValue();
        assertThat(jpql).contains("m.deletedAt is null");
        assertThat(jpql).contains("m.title like :keyword");
        assertThat(jpql).contains(">= :fromInclusive");
        assertThat(jpql).contains("< :toExclusive");
        assertThat(jpql).contains("selectedDateStart0");
        assertThat(jpql).contains("selectedDateEnd0");
        assertThat(jpql).contains("selectedDateStart1");
        assertThat(jpql).contains("selectedDateEnd1");
        assertThat(jpql).contains("function('date'");
        assertThat(jpql).contains("desc");
        assertThat(jpql).contains("asc");
        assertThat(jpql).contains("m.id asc");
        verify(query).setParameter("selectedDateStart0", LocalDate.of(2026, 9, 17).atStartOfDay());
        verify(query).setParameter("selectedDateEnd0", LocalDate.of(2026, 9, 18).atStartOfDay());
        verify(query).setParameter("selectedDateStart1", LocalDate.of(2026, 9, 16).atStartOfDay());
        verify(query).setParameter("selectedDateEnd1", LocalDate.of(2026, 9, 17).atStartOfDay());
    }

    @SuppressWarnings("unchecked")
    private Query queryMock(EntityManager entityManager) {
        Query query = mock(Query.class);
        when(entityManager.createQuery(any(String.class))).thenReturn(query);
        when(query.setParameter(any(String.class), any())).thenReturn(query);
        when(query.setMaxResults(any(Integer.class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(Date.valueOf(LocalDate.of(2026, 9, 17))));
        return query;
    }

    @SuppressWarnings("unchecked")
    private TypedQuery<Meeting> typedQueryMock(EntityManager entityManager) {
        TypedQuery<Meeting> query = mock(TypedQuery.class);
        when(entityManager.createQuery(any(String.class), eq(Meeting.class))).thenReturn(query);
        when(query.setParameter(any(String.class), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        return query;
    }
}
