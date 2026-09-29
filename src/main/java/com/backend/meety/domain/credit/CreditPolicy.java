package com.backend.meety.domain.credit;

import java.time.LocalDate;
import java.time.temporal.IsoFields;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "credit")
public record CreditPolicy(
        long maxBalance,
        long teamCreateGrant,
        long weeklyGrant,
        String weeklyGrantCron,
        long recordingCost,
        long summaryRegenerateCost,
        long aiChatMessageCost,
        long analysisReportCost
) {

    public static String weekKeyOf(LocalDate date) {
        return String.format("%d-W%02d",
                date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }
}
