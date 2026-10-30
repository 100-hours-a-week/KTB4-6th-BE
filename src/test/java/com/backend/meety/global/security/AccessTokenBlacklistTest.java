package com.backend.meety.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.global.config.JwtProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(OutputCaptureExtension.class)
class AccessTokenBlacklistTest {

    private static final JwtProperties PROPERTIES = new JwtProperties(
            "test-jwt-secret-key-for-meety-32bytes!!", Duration.ofMinutes(30), Duration.ofDays(14));
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC);

    private final JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(PROPERTIES, CLOCK);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AccessTokenBlacklist blacklist =
            new AccessTokenBlacklist(redisTemplate, jwtTokenProvider, CLOCK, meterRegistry);

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("AT를 원문이 아닌 해시 키로 남은 만료 시간만큼 등록한다")
    void registerWithHashedKeyAndRemainingTtl() {
        String token = jwtTokenProvider.createAccessToken(1L);
        Clock tenMinutesLater = Clock.offset(CLOCK, Duration.ofMinutes(10));
        AccessTokenBlacklist laterBlacklist =
                new AccessTokenBlacklist(redisTemplate, jwtTokenProvider, tenMinutesLater, meterRegistry);

        laterBlacklist.register(token);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(keyCaptor.capture(), eq("1"), eq(Duration.ofMinutes(20)));
        assertThat(keyCaptor.getValue()).matches("at:blacklist:[0-9a-f]{64}").doesNotContain(token);
    }

    @Test
    @DisplayName("등록과 조회는 같은 키를 쓴다")
    void containsUsesSameKey() {
        String token = jwtTokenProvider.createAccessToken(1L);
        blacklist.register(token);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(keyCaptor.capture(), anyString(), any(Duration.class));
        when(redisTemplate.hasKey(keyCaptor.getValue())).thenReturn(true);

        assertThat(blacklist.contains(token)).isTrue();
    }

    @Test
    @DisplayName("등록하지 않은 AT는 차단되지 않는다")
    void notRegisteredTokenPasses() {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);

        assertThat(blacklist.contains(jwtTokenProvider.createAccessToken(1L))).isFalse();
    }

    @Test
    @DisplayName("이미 만료된 AT는 등록하지 않는다")
    void skipExpiredToken() {
        Clock past = Clock.offset(CLOCK, Duration.ofMinutes(-40));
        String expiredToken = new JwtTokenProvider(PROPERTIES, past).createAccessToken(1L);

        blacklist.register(expiredToken);

        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("위조 토큰과 null은 등록을 무시한다")
    void skipInvalidToken() {
        blacklist.register("not-a-jwt");
        blacklist.register(null);
        blacklist.register(" ");

        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("Redis 등록에 실패해도 예외를 던지지 않는다")
    void registerIgnoresRedisFailure() {
        String token = jwtTokenProvider.createAccessToken(1L);
        doThrow(new RedisConnectionFailureException("down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> blacklist.register(token)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("같은 AT를 여러 번 등록해도 같은 키로 덮어쓴다")
    void registerIsIdempotent() {
        String token = jwtTokenProvider.createAccessToken(1L);

        blacklist.register(token);
        blacklist.register(token);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, times(2)).set(keyCaptor.capture(), eq("1"), any(Duration.class));
        assertThat(keyCaptor.getAllValues()).containsOnly(keyCaptor.getAllValues().getFirst());
    }

    @Test
    @DisplayName("Redis 연결 장애면 Fail-Open하고 장애 로그와 지표를 남긴다")
    void failOpenWhenRedisConnectionUnavailable(CapturedOutput output) {
        String token = jwtTokenProvider.createAccessToken(1L);
        MDC.put("requestId", "request-123");
        when(redisTemplate.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        try {
            assertThat(blacklist.contains(token)).isFalse();
            assertThat(output).contains("event=AT_BLACKLIST_REDIS_UNAVAILABLE")
                    .contains("failOpen=true")
                    .contains("requestId=request-123")
                    .doesNotContain(token);
            assertThat(meterRegistry.counter("security.at.blacklist.redis.fail_open").count()).isEqualTo(1.0);
        } finally {
            MDC.clear();
        }
    }

    @Test
    @DisplayName("Redis 복구 후 블랙리스트에 등록된 AT를 다시 차단한다")
    void blocksTokenAfterRedisRecovers() {
        String token = jwtTokenProvider.createAccessToken(1L);
        when(redisTemplate.hasKey(anyString()))
                .thenThrow(new RedisConnectionFailureException("down"))
                .thenReturn(true);

        assertThat(blacklist.contains(token)).isFalse();
        assertThat(blacklist.contains(token)).isTrue();
    }

    @Test
    @DisplayName("Redis 명령 타임아웃이면 Fail-Open한다")
    void failOpenWhenRedisCommandTimesOut() {
        when(redisTemplate.hasKey(anyString())).thenThrow(new QueryTimeoutException("timeout"));

        assertThat(blacklist.contains(jwtTokenProvider.createAccessToken(1L))).isFalse();
    }

    @Test
    @DisplayName("Redis 데이터 접근 오류는 가용성 장애로 간주해 무시하지 않는다")
    void propagatesUnexpectedRedisDataAccessFailure() {
        when(redisTemplate.hasKey(anyString())).thenThrow(new DataAccessException("wrong Redis data type") { });

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> blacklist.contains(jwtTokenProvider.createAccessToken(1L)))
                .isInstanceOf(DataAccessException.class);
    }
}
