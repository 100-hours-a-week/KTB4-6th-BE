package com.backend.meety.domain.credit.service;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.credit.repository.TeamCreditRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WeeklyCreditGrantSchedulerTest {

    // 2026-09-28 월요일 00:00 KST
    private static final Clock MONDAY_KST = Clock.fixed(
            Instant.parse("2026-09-27T15:00:00Z"), ZoneId.of("Asia/Seoul"));

    private final TeamCreditRepository credits = mock(TeamCreditRepository.class);
    private final WeeklyCreditGrantService service = mock(WeeklyCreditGrantService.class);
    private final WeeklyCreditGrantScheduler scheduler = new WeeklyCreditGrantScheduler(credits, service, MONDAY_KST);

    @Test
    @DisplayName("활성 팀마다 이번 주차 키로 적립을 위임한다")
    void grantsEveryActiveTeam() {
        when(credits.findActiveTeamIds()).thenReturn(List.of(2L, 3L));

        scheduler.grantWeeklyCredits();

        verify(service).grant(2L, "2026-W40");
        verify(service).grant(3L, "2026-W40");
    }

    @Test
    @DisplayName("한 팀이 실패해도 나머지 팀은 계속 적립한다")
    void continuesAfterFailure() {
        when(credits.findActiveTeamIds()).thenReturn(List.of(2L, 3L, 4L));
        doThrow(new RuntimeException("db down")).when(service).grant(3L, "2026-W40");

        scheduler.grantWeeklyCredits();

        verify(service).grant(2L, "2026-W40");
        verify(service).grant(4L, "2026-W40");
    }
}
