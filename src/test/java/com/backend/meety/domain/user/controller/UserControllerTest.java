package com.backend.meety.domain.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.backend.meety.domain.user.dto.UserVoiceConsentResponse;
import com.backend.meety.domain.user.dto.UserVoiceProfileResponse;
import com.backend.meety.domain.user.dto.UserVoiceProfileUploadUrlResponse;
import com.backend.meety.domain.user.service.UserService;
import com.backend.meety.domain.user.service.UserVoiceProfileService;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
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

    @MockitoBean
    private UserVoiceProfileService userVoiceProfileService;

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

    @Test
    @DisplayName("음성 수집 동의는 200으로 동의 시각을 응답한다")
    void consentToVoiceCollection() throws Exception {
        when(userVoiceProfileService.consent(any()))
                .thenReturn(new UserVoiceConsentResponse(LocalDateTime.of(2026, 10, 27, 15, 0)));

        mvc.perform(put("/api/v1/users/me/voice-profile/consent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.consentedAt").value("2026-10-27T15:00:00"));
    }

    @Test
    @DisplayName("사전 녹음 업로드 URL 발급은 201로 응답한다")
    void createVoiceProfileUploadUrl() throws Exception {
        when(userVoiceProfileService.createUploadUrl(any(), eq("audio/mp4")))
                .thenReturn(new UserVoiceProfileUploadUrlResponse(
                        "https://upload", LocalDateTime.of(2026, 10, 27, 15, 10)));

        mvc.perform(post("/api/v1/users/me/voice-profile/upload-url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"audio/mp4\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.uploadUrl").value("https://upload"));
    }

    @Test
    @DisplayName("contentType이 비어 있으면 업로드 URL을 발급하지 않고 400으로 응답한다")
    void createVoiceProfileUploadUrlWithBlankContentType() throws Exception {
        mvc.perform(post("/api/v1/users/me/voice-profile/upload-url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\" \"}"))
                .andExpect(status().isBadRequest());

        verify(userVoiceProfileService, never()).createUploadUrl(any(), any());
    }

    @Test
    @DisplayName("사전 녹음 등록은 본문 없이 호출하고 200으로 등록 여부를 응답한다")
    void registerVoiceProfile() throws Exception {
        when(userVoiceProfileService.register(any())).thenReturn(new UserVoiceProfileResponse(true));

        mvc.perform(post("/api/v1/users/me/voice-profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasVoiceProfile").value(true));
    }
}
