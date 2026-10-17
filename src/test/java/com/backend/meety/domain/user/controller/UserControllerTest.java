package com.backend.meety.domain.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.backend.meety.domain.user.service.UserService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        value = UserController.class,
        properties = {
                "auth.access-token-cookie-name=dev_accessToken",
                "spring.security.oauth2.client.registration.kakao.client-id=test-client",
                "spring.security.oauth2.client.registration.kakao.client-secret=test-secret",
                "spring.security.oauth2.client.registration.kakao.redirect-uri=http://localhost/callback"
        }
)
@AutoConfigureMockMvc(addFilters = false)
class UserControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private UserService userService;

    @Test
    @DisplayName("탈퇴 시 설정한 이름의 쿠키에 담긴 AT를 서비스로 넘긴다")
    void withdrawPassesAccessTokenFromConfiguredCookie() throws Exception {
        mvc.perform(delete("/api/v1/users/me").cookie(new Cookie("dev_accessToken", "access-token")))
                .andExpect(status().isNoContent());

        verify(userService).withdraw(any(), eq("access-token"));
    }

    @Test
    @DisplayName("설정한 이름과 다른 쿠키는 읽지 않는다")
    void ignoresOtherCookieName() throws Exception {
        mvc.perform(delete("/api/v1/users/me").cookie(new Cookie("accessToken", "access-token")))
                .andExpect(status().isNoContent());

        verify(userService).withdraw(any(), isNull());
    }
}
