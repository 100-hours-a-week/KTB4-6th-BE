package com.backend.meety.domain.meeting.repository;

import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingStatus;
import com.backend.meety.domain.meeting.repository.support.MeetingListSearchCondition;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
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
    public List<LocalDate> findMeetingDatesByCondition(MeetingListSearchCondition condition) {
        Map<String, Object> parameters = new HashMap<>();
        StringBuilder jpql = new StringBuilder("""
                select distinct %s
                from Meeting m
                where m.team.id = :teamId
                  and m.deletedAt is null
                """.formatted(EFFECTIVE_DATE));
        putBaseParameters(parameters, condition);

        appendKeywordCondition(jpql, parameters, condition);
        appendDateRangeCondition(jpql, parameters, condition);
        appendDateCursorCondition(jpql, parameters, condition);

        jpql.append("""
                order by
                  %s desc
                """.formatted(EFFECTIVE_DATE));

        Query query = entityManager.createQuery(jpql.toString());
        parameters.forEach(query::setParameter);
        query.setMaxResults(condition.limit());
        return query.getResultList().stream()
                .map(this::toLocalDate)
                .toList();
    }

    @Override
    public List<Meeting> findMeetingsByDates(MeetingListSearchCondition condition, List<LocalDate> dates) {
        if (dates.isEmpty()) {
            return List.of();
        }

        Map<String, Object> parameters = new HashMap<>();
        StringBuilder jpql = new StringBuilder("""
                select m
                from Meeting m
                where m.team.id = :teamId
                  and m.deletedAt is null
                """);
        putBaseParameters(parameters, condition);

        appendKeywordCondition(jpql, parameters, condition);
        appendDateRangeCondition(jpql, parameters, condition);
        appendSelectedDatesCondition(jpql, parameters, dates);

        jpql.append("""
                order by
                  %s desc,
                  %s asc,
                  m.id asc
                """.formatted(EFFECTIVE_DATE, EFFECTIVE_START_AT));

        TypedQuery<Meeting> query = entityManager.createQuery(jpql.toString(), Meeting.class);
        parameters.forEach(query::setParameter);
        return query.getResultList();
    }

    private void putBaseParameters(Map<String, Object> parameters, MeetingListSearchCondition condition) {
        parameters.put("teamId", condition.teamId());
        parameters.put("waitingStatus", MeetingStatus.WAITING);
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

    private void appendDateCursorCondition(
            StringBuilder jpql,
            Map<String, Object> parameters,
            MeetingListSearchCondition condition
    ) {
        if (condition.cursorDate() == null) {
            return;
        }

        jpql.append("  and ").append(EFFECTIVE_START_AT).append(" < :cursorDateStart\n");
        parameters.put("cursorDateStart", condition.cursorDate().atStartOfDay());
    }

    private void appendSelectedDatesCondition(
            StringBuilder jpql,
            Map<String, Object> parameters,
            List<LocalDate> dates
    ) {
        jpql.append("  and (\n");
        for (int i = 0; i < dates.size(); i++) {
            if (i > 0) {
                jpql.append("    or\n");
            }
            String startParameter = "selectedDateStart" + i;
            String endParameter = "selectedDateEnd" + i;
            jpql.append("    (")
                    .append(EFFECTIVE_START_AT)
                    .append(" >= :")
                    .append(startParameter)
                    .append(" and ")
                    .append(EFFECTIVE_START_AT)
                    .append(" < :")
                    .append(endParameter)
                    .append(")\n");
            LocalDate date = dates.get(i);
            parameters.put(startParameter, date.atStartOfDay());
            parameters.put(endParameter, date.plusDays(1).atStartOfDay());
        }
        jpql.append("  )\n");
    }

    private LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof CharSequence text) {
            return LocalDate.parse(text);
        }
        throw new IllegalStateException("Unsupported effective date type: " + value.getClass().getName());
    }
}
