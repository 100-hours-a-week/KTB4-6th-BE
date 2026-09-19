package com.backend.meety.domain.credit.dto;

import com.backend.meety.domain.credit.CreditPolicy;
import com.backend.meety.domain.credit.entity.TeamCredit;

public record TeamCreditResponse(
        Long balance,
        long maxBalance
) {

    public static TeamCreditResponse from(TeamCredit teamCredit) {
        return new TeamCreditResponse(teamCredit.getBalance(), CreditPolicy.MAX_BALANCE);
    }
}
