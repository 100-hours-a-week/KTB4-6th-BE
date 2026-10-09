package com.backend.meety.domain.notification.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.backend.meety.domain.notification.dto.NotificationItemResponse;
import com.backend.meety.domain.notification.dto.NotificationListResponse;
import com.backend.meety.domain.notification.dto.NotificationReadRequest;
import com.backend.meety.domain.notification.dto.NotificationReadResponse;
import com.backend.meety.domain.notification.dto.NotificationsReadResponse;
import com.backend.meety.domain.notification.entity.NotificationReferenceType;
import com.backend.meety.domain.notification.entity.NotificationType;
import com.backend.meety.domain.notification.exception.NotificationErrorCode;
import com.backend.meety.domain.notification.exception.NotificationException;
import com.backend.meety.domain.notification.service.NotificationService;
import com.backend.meety.global.exception.GlobalExceptionHandler;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class NotificationControllerTest {

    private NotificationService notificationService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new NotificationController(notificationService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("알림 목록을 조회한다")
    void getNotifications() throws Exception {
        // 테스트 목적:
        // 로그인 사용자의 알림 목록 조회 요청이 서비스 결과를 공통 응답 형식으로 반환하고
        // cursor와 size 요청 파라미터를 서비스에 전달하는지 검증한다.

        // given
        when(notificationService.getNotifications(1L, "30", 20)).thenReturn(new NotificationListResponse(
                List.of(new NotificationItemResponse(
                        29L,
                        NotificationType.MEETING_STARTED,
                        "회의가 시작되었습니다",
                        NotificationReferenceType.MEETING,
                        100L,
                        false,
                        LocalDateTime.of(2026, 10, 9, 10, 0)
                )),
                3L,
                null,
                false
        ));

        // when, then
        mvc.perform(get("/api/v1/notifications")
                        .param("cursor", "30")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.notifications[0].notificationId").value(29))
                .andExpect(jsonPath("$.data.notifications[0].type").value("MEETING_STARTED"))
                .andExpect(jsonPath("$.data.unreadCount").value(3))
                .andExpect(jsonPath("$.data.hasNext").value(false));

        verify(notificationService).getNotifications(1L, "30", 20);
    }

    @Test
    @DisplayName("모든 알림을 읽음 처리한다")
    void markAllAsRead() throws Exception {
        // 테스트 목적:
        // 모든 알림 읽음 처리 요청이 body의 isRead 값을 서비스에 전달하고
        // 처리 건수와 남은 미읽음 수를 반환하는지 검증한다.

        // given
        when(notificationService.markAllAsRead(eq(1L), eq(new NotificationReadRequest(true))))
                .thenReturn(new NotificationsReadResponse(3L, 0L));

        // when, then
        mvc.perform(patch("/api/v1/notifications")
                        .contentType("application/json")
                        .content("{\"isRead\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.readCount").value(3))
                .andExpect(jsonPath("$.data.unreadCount").value(0));

        verify(notificationService).markAllAsRead(1L, new NotificationReadRequest(true));
    }

    @Test
    @DisplayName("알림 하나를 읽음 처리한다")
    void markAsRead() throws Exception {
        // 테스트 목적:
        // 개별 알림 읽음 처리 요청이 알림 ID와 isRead 값을 서비스에 전달하고
        // 읽음 상태와 남은 미읽음 수를 반환하는지 검증한다.

        // given
        when(notificationService.markAsRead(eq(1L), eq(10L), eq(new NotificationReadRequest(true))))
                .thenReturn(new NotificationReadResponse(10L, true, 2L));

        // when, then
        mvc.perform(patch("/api/v1/notifications/10")
                        .contentType("application/json")
                        .content("{\"isRead\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.notificationId").value(10))
                .andExpect(jsonPath("$.data.isRead").value(true))
                .andExpect(jsonPath("$.data.unreadCount").value(2));

        verify(notificationService).markAsRead(1L, 10L, new NotificationReadRequest(true));
    }

    @Test
    @DisplayName("잘못된 읽음 상태는 400을 반환한다")
    void invalidReadStatusReturnsBadRequest() throws Exception {
        // 테스트 목적:
        // false 또는 누락된 읽음 상태를 서비스가 거부하면
        // Controller가 공통 에러 응답으로 반환하는지 검증한다.

        // given
        when(notificationService.markAllAsRead(eq(1L), eq(new NotificationReadRequest(false))))
                .thenThrow(new NotificationException(NotificationErrorCode.INVALID_NOTIFICATION_READ_STATUS));

        // when, then
        mvc.perform(patch("/api/v1/notifications")
                        .contentType("application/json")
                        .content("{\"isRead\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_NOTIFICATION_READ_STATUS"));
    }

    @Test
    @DisplayName("본인 알림이 아니면 404를 반환한다")
    void otherUserNotificationReturnsNotFound() throws Exception {
        // 테스트 목적:
        // 존재하지 않거나 다른 사용자의 알림 읽음 요청이
        // NOTIFICATION_NOT_FOUND 공통 에러 응답으로 반환되는지 검증한다.

        // given
        when(notificationService.markAsRead(eq(1L), eq(10L), eq(new NotificationReadRequest(true))))
                .thenThrow(new NotificationException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));

        // when, then
        mvc.perform(patch("/api/v1/notifications/10")
                        .contentType("application/json")
                        .content("{\"isRead\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("NOTIFICATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("알림 하나를 삭제하면 204를 반환한다")
    void deleteNotification() throws Exception {
        // 테스트 목적:
        // 개별 알림 삭제 요청이 서비스에 위임되고
        // 성공 시 기존 삭제 API 스타일에 맞춰 204 No Content를 반환하는지 검증한다.

        // when, then
        mvc.perform(delete("/api/v1/notifications/10"))
                .andExpect(status().isNoContent());

        verify(notificationService).delete(1L, 10L);
    }
}
