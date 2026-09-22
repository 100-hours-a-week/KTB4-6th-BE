package com.backend.meety.domain.credit;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 크레딧 정책값. 기획 정책이 바뀌면 이 클래스만 수정한다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CreditPolicy {

    public static final long MAX_BALANCE = 1000L;

    public static final long TEAM_CREATE_GRANT = 1000L;

    public static final long WEEKLY_GRANT = 50L;

    public static final long RECORDING_COST = 20L;

    public static final long SUMMARY_REGENERATE_COST = 3L;

    public static final long AI_CHAT_MESSAGE_COST = 1L;

    public static final long ANALYSIS_REPORT_COST = 5L;
}
