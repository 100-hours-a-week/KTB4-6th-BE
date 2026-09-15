package com.backend.meety.domain.meeting.repository;

import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingStatus;
import com.backend.meety.domain.meeting.repository.support.MeetingCursor;
import com.backend.meety.domain.meeting.repository.support.MeetingListSearchCondition;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class MeetingRepositoryImpl implements MeetingRepositoryCustom {

    private static final String EFFECTIVE_START_AT = """
            case
              when m.status = :waitingStatus then m.scheduledAt
              else m.startedAt
            end
            """;
    private static final String EFFECTIVE_DATE = "function('date', " + EFFECTIVE_START_AT + ")";

    private final EntityManager entityManager;

    @Override
    public List<Meeting> findMeetingsByCondition(MeetingListSearchCondition condition) {
        Map<String, Object> parameters = new HashMap<>();
        StringBuilder jpql = new StringBuilder("""
                select m
                from Meeting m
                where m.team.id = :teamId
                  and m.deletedAt is null
                """);
        parameters.put("teamId", condition.teamId());
        parameters.put("waitingStatus", MeetingStatus.WAITING);

        appendKeywordCondition(jpql, parameters, condition);
        appendDateRangeCondition(jpql, parameters, condition);
        appendCursorCondition(jpql, parameters, condition.cursor());

        jpql.append("""
                order by
                  %s desc,
                  %s asc,
                  m.id asc
                """.formatted(EFFECTIVE_DATE, EFFECTIVE_START_AT));

        TypedQuery<Meeting> query = entityManager.createQuery(jpql.toString(), Meeting.class);
        parameters.forEach(query::setParameter);
        query.setMaxResults(condition.limit());
        return query.getResultList();
    }

    private void appendKeywordCondition(
            StringBuilder jpql,
            Map<String, Object> parameters,
            MeetingListSearchCondition condition
    ) {
        if (condition.keyword() == null) {
            return;
        }

        jpql.append("  and m.title like :keyword\n");
        parameters.put("keyword", "%" + condition.keyword() + "%");
    }

    private void appendDateRangeCondition(
            StringBuilder jpql,
            Map<String, Object> parameters,
            MeetingListSearchCondition condition
    ) {
        if (condition.fromInclusive() != null) {
            jpql.append("  and ").append(EFFECTIVE_START_AT).append(" >= :fromInclusive\n");
            parameters.put("fromInclusive", condition.fromInclusive());
        }
        if (condition.toExclusive() != null) {
            jpql.append("  and ").append(EFFECTIVE_START_AT).append(" < :toExclusive\n");
            parameters.put("toExclusive", condition.toExclusive());
        }
    }

    private void appendCursorCondition(
            StringBuilder jpql,
            Map<String, Object> parameters,
            MeetingCursor cursor
    ) {
        if (cursor == null) {
            return;
        }

        jpql.append("""
                  and (
                    %s < :cursorDateStart
                    or (
                      %s >= :cursorDateStart
                      and %s < :cursorNextDateStart
                      and %s > :cursorEffectiveStartAt
                    )
                    or (
                      %s >= :cursorDateStart
                      and %s < :cursorNextDateStart
                      and %s = :cursorEffectiveStartAt
                      and m.id > :cursorMeetingId
                    )
                  )
                """.formatted(
                EFFECTIVE_START_AT,
                EFFECTIVE_START_AT,
                EFFECTIVE_START_AT,
                EFFECTIVE_START_AT,
                EFFECTIVE_START_AT,
                EFFECTIVE_START_AT,
                EFFECTIVE_START_AT
        ));
        LocalDate cursorDate = cursor.effectiveStartAt().toLocalDate();
        parameters.put("cursorDateStart", cursorDate.atStartOfDay());
        parameters.put("cursorNextDateStart", cursorDate.plusDays(1).atStartOfDay());
        parameters.put("cursorEffectiveStartAt", cursor.effectiveStartAt());
        parameters.put("cursorMeetingId", cursor.meetingId());
    }
}
