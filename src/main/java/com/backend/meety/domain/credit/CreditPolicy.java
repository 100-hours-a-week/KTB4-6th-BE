package com.backend.meety.domain.credit;

import java.time.LocalDate;
import java.time.temporal.IsoFields;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CreditPolicy {

    public static final long MAX_BALANCE = 1000L;

    public static final long TEAM_CREATE_GRANT = 1000L;

    public static final long WEEKLY_GRANT = 50L;

    /**
     * 매주 월요일 00:00 KST. zone은 스케줄러 애너테이션에서 지정한다.
     */
    public static final String WEEKLY_GRANT_CRON = "0 0 0 * * MON";

    public static final long RECORDING_COST = 20L;

    public static final long SUMMARY_REGENERATE_COST = 3L;

    public static final long AI_CHAT_MESSAGE_COST = 1L;

    public static final long ANALYSIS_REPORT_COST = 5L;

    /**
     * 정기 적립 멱등키의 주차 표기. ISO 주 기준 연도-주차로, 연말연시 주는 주가 속한 연도를 따른다 (예 2027-01-01 → 2026-W53).
     */
    public static String weekKeyOf(LocalDate date) {
        return String.format("%d-W%02d",
                date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }
}
