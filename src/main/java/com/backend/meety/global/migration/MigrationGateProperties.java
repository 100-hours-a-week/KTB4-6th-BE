package com.backend.meety.global.migration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;


@ConfigurationProperties("migration.write-gate")
public record MigrationGateProperties(

        @DefaultValue("false") boolean enabled,

        @DefaultValue("true") boolean initiallyClosed,

        @DefaultValue("10s") Duration maxWait,

        @DefaultValue("100") int maxPending,

        @DefaultValue("") String adminToken) {
    public MigrationGateProperties {
        if (maxWait == null || maxWait.isZero() || maxWait.isNegative()
                || maxWait.compareTo(Duration.ofMinutes(1)) > 0 || maxPending < 1) {
            throw new IllegalArgumentException("게이트 대기는 0~60초 사이, 대기 한도는 1 이상이어야 합니다.");
        }
        if (enabled && (adminToken == null || adminToken.length() < 32)) {
            throw new IllegalArgumentException("게이트 활성화 시 32자 이상의 관리 토큰이 필요합니다.");
        }
    }
}
