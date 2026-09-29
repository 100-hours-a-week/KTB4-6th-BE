package com.backend.meety.domain.auth.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.backend.meety.domain.auth.dto.LoginResponse;
import com.backend.meety.domain.auth.service.AuthService;
import com.backend.meety.domain.auth.service.LocalAuthService;
import com.backend.meety.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthControllerTest {

    private static final LoginResponse TOKENS = new LoginResponse(1L, "access", "refresh", 1800L, 1209600L);

    private AuthService authService;
    private LocalAuthService localAuthService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        localAuthService = mock(LocalAuthService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(authService, localAuthService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("local 로그인은 provider 로그인이 아니라 로컬 서비스로 간다")
    void localLoginRoutesToLocalService() throws Exception {
        when(localAuthService.login("loadtest-0001", "secret")).thenReturn(TOKENS);

        mvc.perform(post("/api/v1/auth/local/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"loadtest-0001\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value(1));
        verify(authService, never()).login(anyString(), anyString());
    }

    @Test
    @DisplayName("local 가입은 201로 토큰을 돌려준다")
    void signupReturnsCreated() throws Exception {
        when(localAuthService.signup("loadtest-0001", "secret")).thenReturn(TOKENS);

        mvc.perform(post("/api/v1/auth/local/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"loadtest-0001\",\"password\":\"secret\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accessToken").value("access"));
    }

    @Test
    @DisplayName("아이디가 비어 있으면 400이다")
    void signupRejectsBlankLoginId() throws Exception {
        mvc.perform(post("/api/v1/auth/local/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\" \",\"password\":\"secret\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT_VALUE"));
        verify(localAuthService, never()).signup(anyString(), anyString());
    }

    @Test
    @DisplayName("kakao 로그인은 그대로 provider 로그인으로 간다")
    void providerLoginStillRoutesToAuthService() throws Exception {
        when(authService.login("kakao", "code-1")).thenReturn(TOKENS);

        mvc.perform(post("/api/v1/auth/kakao/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorizationCode\":\"code-1\"}"))
                .andExpect(status().isOk());
        verify(localAuthService, never()).login(anyString(), anyString());
    }
}
