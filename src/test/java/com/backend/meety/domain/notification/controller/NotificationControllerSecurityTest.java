package com.backend.meety.domain.notification.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.backend.meety.domain.notification.realtime.NotificationSseService;
import com.backend.meety.domain.notification.service.NotificationService;
import com.backend.meety.global.config.CorsProperties;
import com.backend.meety.global.security.AccessTokenBlacklist;
import com.backend.meety.global.security.CustomAuthenticationEntryPoint;
import com.backend.meety.global.security.JwtTokenProvider;
import com.backend.meety.global.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        value = NotificationController.class,
        properties = {
                "spring.security.oauth2.client.registration.kakao.client-id=test-client",
                "spring.security.oauth2.client.registration.kakao.client-secret=test-secret",
                "spring.security.oauth2.client.registration.kakao.redirect-uri=http://localhost/callback"
        }
)
@AutoConfigureMockMvc
@Import({SecurityConfig.class, CustomAuthenticationEntryPoint.class})
class NotificationControllerSecurityTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private NotificationService notificationService;

    @MockitoBean
    private NotificationSseService notificationSseService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private AccessTokenBlacklist accessTokenBlacklist;

    @MockitoBean
    private CorsProperties corsProperties;

    @Test
    @DisplayName("미인증 사용자는 전역 알림 SSE에 연결할 수 없다")
    void unauthenticatedConnectEventsFails() throws Exception {
        // 테스트 목적:
        // accessToken 쿠키로 인증되지 않은 요청은
        // 전역 알림 SSE 연결 전에 SecurityFilterChain에서 거부되는지 검증한다.

        // when, then
        mvc.perform(get("/api/v1/notifications/events").accept("text/event-stream"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(notificationSseService);
    }
}
