package com.backend.meety.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class InternalApiKeyFilterTest {

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("공유 키가 일치하면 내부 요청으로 인증한다")
    void authenticatesMatchingKey() throws Exception {
        filter("secret-key", "secret-key");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    @DisplayName("공유 키가 없거나 다르면 인증하지 않는다")
    void rejectsMissingOrWrongKey() throws Exception {
        filter("secret-key", null);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        filter("secret-key", "wrong-key");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("서버에 공유 키가 설정되지 않았으면 어떤 요청도 인증하지 않는다")
    void rejectsAllWhenKeyNotConfigured() throws Exception {
        filter("", "");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        filter(null, "anything");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private void filter(String configuredKey, String requestKey) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (requestKey != null) {
            request.addHeader(InternalApiHeader.API_KEY, requestKey);
        }
        new InternalApiKeyFilter(configuredKey).doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    }
}
