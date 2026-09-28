package com.backend.meety.domain.credit.service;

import com.backend.meety.domain.credit.CreditPolicy;
import com.backend.meety.domain.credit.repository.TeamCreditRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WeeklyCreditGrantScheduler {

    private static final ZoneId KST_ZONE_ID = ZoneId.of("Asia/Seoul");

    private final TeamCreditRepository teamCreditRepository;
    private final WeeklyCreditGrantService weeklyCreditGrantService;
    private final Clock clock;

    @Scheduled(cron = CreditPolicy.WEEKLY_GRANT_CRON, zone = "Asia/Seoul")
    public void grantWeeklyCredits() {
        String weekKey = CreditPolicy.weekKeyOf(LocalDate.now(clock.withZone(KST_ZONE_ID)));
        List<Long> teamIds = teamCreditRepository.findActiveTeamIds();
        int granted = 0;
        for (Long teamId : teamIds) {
            try {
                if (weeklyCreditGrantService.grant(teamId, weekKey)) {
                    granted++;
                }
            } catch (Exception e) {
                log.error("주간 크레딧 적립에 실패했습니다. teamId={}, weekKey={}", teamId, weekKey, e);
            }
        }
        log.info("주간 크레딧 적립을 마쳤습니다. weekKey={}, teams={}, granted={}", weekKey, teamIds.size(), granted);
    }
}
