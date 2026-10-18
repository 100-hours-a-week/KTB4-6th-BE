package com.backend.meety.domain.credit.dto;

import com.backend.meety.domain.credit.entity.CreditLedger;
import java.util.List;

public record CreditLedgerListResponse(
        List<CreditLedgerResponse> ledgers,
        Long nextCursor,
        boolean hasNext
) {

    public static CreditLedgerListResponse of(List<CreditLedger> fetched, int pageSize) {
        boolean hasNext = fetched.size() > pageSize;
        List<CreditLedger> page = hasNext ? fetched.subList(0, pageSize) : fetched;
        Long nextCursor = hasNext ? page.getLast().getId() : null;
        return new CreditLedgerListResponse(
                page.stream().map(CreditLedgerResponse::from).toList(),
                nextCursor,
                hasNext);
    }
}
