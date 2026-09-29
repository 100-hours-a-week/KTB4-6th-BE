package com.backend.meety.domain.credit;

public final class TestCreditPolicy {

    public static final long MAX_BALANCE = 1000L;
    public static final long TEAM_CREATE_GRANT = 200L;
    public static final long WEEKLY_GRANT = 200L;
    public static final long RECORDING_COST = 20L;
    public static final long SUMMARY_REGENERATE_COST = 3L;

    public static final CreditPolicy DEFAULT = new CreditPolicy(
            MAX_BALANCE,
            TEAM_CREATE_GRANT,
            WEEKLY_GRANT,
            "0 0 0 * * MON",
            RECORDING_COST,
            SUMMARY_REGENERATE_COST,
            1L,
            5L
    );

    private TestCreditPolicy() {
    }
}
