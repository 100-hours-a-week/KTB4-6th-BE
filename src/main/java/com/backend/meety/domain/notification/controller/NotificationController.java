package com.backend.meety.domain.notification.controller;

import com.backend.meety.domain.notification.dto.NotificationListResponse;
import com.backend.meety.domain.notification.dto.NotificationReadRequest;
import com.backend.meety.domain.notification.dto.NotificationReadResponse;
import com.backend.meety.domain.notification.dto.NotificationsReadResponse;
import com.backend.meety.domain.notification.realtime.NotificationSseService;
import com.backend.meety.domain.notification.service.NotificationService;
import com.backend.meety.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private static final String X_ACCEL_BUFFERING = "X-Accel-Buffering";
    private static final String X_ACCEL_BUFFERING_OFF = "no";

    private final NotificationService notificationService;
    private final NotificationSseService notificationSseService;

    @GetMapping
    public ResponseEntity<ApiResponse<NotificationListResponse>> getNotifications(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                notificationService.getNotifications(userId, cursor, size)));
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> connectEvents(@AuthenticationPrincipal Long userId) {
        SseEmitter emitter = notificationSseService.connect(userId);
        return ResponseEntity.ok()
                .header(X_ACCEL_BUFFERING, X_ACCEL_BUFFERING_OFF)
                .cacheControl(CacheControl.noCache())
                .body(emitter);
    }

    @PatchMapping
    public ResponseEntity<ApiResponse<NotificationsReadResponse>> markAllAsRead(
            @AuthenticationPrincipal Long userId,
            @RequestBody NotificationReadRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                notificationService.markAllAsRead(userId, request)));
    }

    @PatchMapping("/{notificationId}")
    public ResponseEntity<ApiResponse<NotificationReadResponse>> markAsRead(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long notificationId,
            @RequestBody NotificationReadRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                notificationService.markAsRead(userId, notificationId, request)));
    }

    @DeleteMapping("/{notificationId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long notificationId
    ) {
        notificationService.delete(userId, notificationId);
        return ResponseEntity.noContent().build();
    }
}
