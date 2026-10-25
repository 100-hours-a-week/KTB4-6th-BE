package com.backend.meety.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ReadAfterWriteFilterTest {

    private static final long NOW = 1_000_000L;
    private static final Duration WINDOW = Duration.ofSeconds(3);

    private final ReadAfterWriteFilter filter = new ReadAfterWriteFilter(
            new ReadAfterWriteProperties(WINDOW), Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));

    @AfterEach
    void clearContext() {
        ReadMasterContext.clear();
    }

    @Test
    @DisplayName("쓰기 요청은 Master 읽기 쿠키를 발급하고 요청 안의 조회도 Master로 보낸다")
    void writeRequestIssuesCookieAndForcesMaster() throws Exception {
        // 테스트 목적:
        // 쓰기 요청은 이후 요청을 위한 쿠키를 발급하고,
        // 트랜잭션 없이 하는 조회까지 Master를 쓰도록 요청 처리 동안 Master 표시를 켜는지 검증한다.

        // given
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/meetings");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean forced = new AtomicBoolean();

        // when
        filter.doFilter(request, response, chainRecording(forced));

        // then
        assertThat(forced).isTrue();
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
                .contains(ReadAfterWriteFilter.COOKIE_NAME + "=" + (NOW + WINDOW.toMillis()))
                .contains("Max-Age=3")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Lax");
        assertThat(ReadMasterContext.isForced()).isFalse();
    }

    @Test
    @DisplayName("유효한 쿠키가 있는 조회 요청은 Master로 보낸다")
    void readRequestWithValidCookieForcesMaster() throws Exception {
        // 테스트 목적:
        // 쓰기 직후 시간 안에 들어온 조회 요청은 쿠키를 보고 Master로 보내고,
        // 조회 요청에는 새 쿠키를 발급하지 않는지 검증한다.

        // given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/meetings/1");
        request.setCookies(new Cookie(ReadAfterWriteFilter.COOKIE_NAME, Long.toString(NOW + 1_000L)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean forced = new AtomicBoolean();

        // when
        filter.doFilter(request, response, chainRecording(forced));

        // then
        assertThat(forced).isTrue();
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    @DisplayName("만료된 쿠키나 쿠키가 없는 조회 요청은 Master로 보내지 않는다")
    void readRequestWithoutValidCookieUsesReplica() throws Exception {
        // 테스트 목적:
        // 시간이 지난 쿠키와 쿠키 없는 조회 요청은 Master 표시 없이 처리되는지 검증한다.

        // given
        MockHttpServletRequest expired = new MockHttpServletRequest("GET", "/api/v1/meetings/1");
        expired.setCookies(new Cookie(ReadAfterWriteFilter.COOKIE_NAME, Long.toString(NOW)));
        MockHttpServletRequest noCookie = new MockHttpServletRequest("GET", "/api/v1/meetings/1");
        AtomicBoolean expiredForced = new AtomicBoolean();
        AtomicBoolean noCookieForced = new AtomicBoolean();

        // when
        filter.doFilter(expired, new MockHttpServletResponse(), chainRecording(expiredForced));
        filter.doFilter(noCookie, new MockHttpServletResponse(), chainRecording(noCookieForced));

        // then
        assertThat(expiredForced).isFalse();
        assertThat(noCookieForced).isFalse();
    }

    @Test
    @DisplayName("허용 시간보다 먼 쿠키나 형식이 잘못된 쿠키는 무시한다")
    void tamperedCookieIsIgnored() throws Exception {
        // 테스트 목적:
        // 조작된 쿠키로 Master 읽기를 오래 유지하거나 잘못된 값으로 오류가 나지 않는지 검증한다.

        // given
        MockHttpServletRequest farFuture = new MockHttpServletRequest("GET", "/api/v1/meetings/1");
        farFuture.setCookies(new Cookie(ReadAfterWriteFilter.COOKIE_NAME,
                Long.toString(NOW + WINDOW.toMillis() + 1L)));
        MockHttpServletRequest malformed = new MockHttpServletRequest("GET", "/api/v1/meetings/1");
        malformed.setCookies(new Cookie(ReadAfterWriteFilter.COOKIE_NAME, "not-a-number"));
        AtomicBoolean farFutureForced = new AtomicBoolean();
        AtomicBoolean malformedForced = new AtomicBoolean();

        // when
        filter.doFilter(farFuture, new MockHttpServletResponse(), chainRecording(farFutureForced));
        filter.doFilter(malformed, new MockHttpServletResponse(), chainRecording(malformedForced));

        // then
        assertThat(farFutureForced).isFalse();
        assertThat(malformedForced).isFalse();
    }

    @Test
    @DisplayName("AI 서버 내부 API는 쿠키 없이도 Master로 보낸다")
    void internalApiForcesMaster() throws Exception {
        // 테스트 목적:
        // 사용자 쿠키가 없는 서버 간 콜백인 내부 API는 쓰기 직후 상태를 읽도록 항상 Master로 보내는지 검증한다.

        // given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/v1/ai-requests/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean forced = new AtomicBoolean();

        // when
        filter.doFilter(request, response, chainRecording(forced));

        // then
        assertThat(forced).isTrue();
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    @DisplayName("Master 표시는 백그라운드 작업 동안만 켜진다")
    void taskDecoratorForcesMasterDuringTask() {
        // 테스트 목적:
        // 쓰기 직후 실행되는 백그라운드 작업이 Master를 쓰도록 작업 동안 표시를 켜고,
        // 작업이 끝나면 스레드에 표시가 남지 않는지 검증한다.

        // given
        AtomicBoolean forced = new AtomicBoolean();
        Runnable task = ReadMasterContext.taskDecorator().decorate(() -> forced.set(ReadMasterContext.isForced()));

        // when
        task.run();

        // then
        assertThat(forced).isTrue();
        assertThat(ReadMasterContext.isForced()).isFalse();
    }

    private MockFilterChain chainRecording(AtomicBoolean forced) {
        return new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest req,
                                   jakarta.servlet.http.HttpServletResponse res) {
                forced.set(ReadMasterContext.isForced());
            }
        });
    }
}
