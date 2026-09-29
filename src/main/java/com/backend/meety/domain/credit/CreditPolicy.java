package com.backend.meety.domain.credit;

import java.time.LocalDate;
import java.time.temporal.IsoFields;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CreditPolicy {

    public static final long MAX_BALANCE = 1000L;

    public static final long TEAM_CREATE_GRANT = 100L;

    public static final long WEEKLY_GRANT = 50L;

    public static final String WEEKLY_GRANT_CRON = "0 0 0 * * MON";

    public static final long RECORDING_COST = 20L;

    public static final long SUMMARY_REGENERATE_COST = 3L;

    public static final long AI_CHAT_MESSAGE_COST = 1L;

    public static final long ANALYSIS_REPORT_COST = 5L;

    public static String weekKeyOf(LocalDate date) {
        return String.format("%d-W%02d",
                date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }
}
