package com.backend.meety.domain.meeting.controller;

import com.backend.meety.domain.meeting.dto.MeetingCreateRequest;
import com.backend.meety.domain.meeting.dto.MeetingCreateResponse;
import com.backend.meety.domain.meeting.dto.MeetingCalendarResponse;
import com.backend.meety.domain.meeting.dto.MeetingDetailResponse;
import com.backend.meety.domain.meeting.dto.MeetingInProgressResponse;
import com.backend.meety.domain.meeting.dto.MeetingListResponse;
import com.backend.meety.domain.meeting.dto.MeetingParticipantListResponse;
import com.backend.meety.domain.meeting.dto.MeetingParticipantResponse;
import com.backend.meety.domain.meeting.dto.MeetingUpdateRequest;
import com.backend.meety.domain.meeting.dto.MeetingUpdateResponse;
import com.backend.meety.domain.meeting.realtime.MeetingSseService;
import com.backend.meety.domain.meeting.realtime.SseHeaders;
import com.backend.meety.domain.meeting.service.MeetingParticipantService;
import com.backend.meety.domain.meeting.service.MeetingService;
import com.backend.meety.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class MeetingController {

    private final MeetingService meetingService;
    private final MeetingParticipantService meetingParticipantService;
    private final MeetingSseService meetingSseService;

    @PostMapping("/teams/{teamId}/meetings")
    public ResponseEntity<ApiResponse<MeetingCreateResponse>> createMeeting(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long teamId,
            @Valid @RequestBody MeetingCreateRequest request
    ) {
        MeetingCreateResponse response = meetingService.createMeeting(userId, teamId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }

    @GetMapping("/meetings/{meetingId}")
    public ResponseEntity<ApiResponse<MeetingDetailResponse>> getMeeting(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long meetingId
    ) {
        return ResponseEntity.ok(ApiResponse.success(meetingService.getMeeting(userId, meetingId)));
    }

    @GetMapping("/teams/{teamId}/meetings")
    public ResponseEntity<ApiResponse<MeetingListResponse>> getMeetings(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long teamId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String cursor
    ) {
        MeetingListResponse response = meetingService.getMeetings(userId, teamId, keyword, from, to, cursor);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/teams/{teamId}/meetings/in-progress")
    public ResponseEntity<ApiResponse<MeetingInProgressResponse>> hasInProgressMeeting(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long teamId
    ) {
        MeetingInProgressResponse response = meetingService.hasInProgressMeeting(userId, teamId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/teams/{teamId}/meeting-calendar")
    public ResponseEntity<ApiResponse<MeetingCalendarResponse>> getMeetingCalendar(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long teamId,
            @RequestParam @NotNull(message = "조회 연도를 입력해주세요.") Integer year,
            @RequestParam @NotNull(message = "조회 월을 입력해주세요.")
            @Min(value = 1, message = "조회 월은 1월 이상이어야 합니다.")
            @Max(value = 12, message = "조회 월은 12월 이하이어야 합니다.")
            Integer month
    ) {
        MeetingCalendarResponse response = meetingService.getMeetingCalendar(userId, teamId, year, month);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PatchMapping("/meetings/{meetingId}")
    public ResponseEntity<ApiResponse<MeetingUpdateResponse>> updateMeeting(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long meetingId,
            @Valid @RequestBody MeetingUpdateRequest request
    ) {
        MeetingUpdateResponse response = meetingService.updateMeeting(userId, meetingId, request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @DeleteMapping("/meetings/{meetingId}")
    public ResponseEntity<Void> deleteMeeting(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long meetingId
    ) {
        meetingService.deleteMeeting(userId, meetingId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/meetings/{meetingId}/participants")
    public ResponseEntity<ApiResponse<MeetingParticipantResponse>> joinMeeting(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long meetingId
    ) {
        MeetingParticipantResponse response = meetingParticipantService.joinMeeting(userId, meetingId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }

    @GetMapping("/meetings/{meetingId}/participants")
    public ResponseEntity<ApiResponse<MeetingParticipantListResponse>> getParticipants(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long meetingId
    ) {
        return ResponseEntity.ok(ApiResponse.success(meetingParticipantService.getParticipants(userId, meetingId)));
    }

    @GetMapping(value = "/meetings/{meetingId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> connectEvents(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long meetingId
    ) {
        return ResponseEntity.ok()
                .header(SseHeaders.X_ACCEL_BUFFERING, SseHeaders.X_ACCEL_BUFFERING_OFF)
                .cacheControl(CacheControl.noCache())
                .body(meetingSseService.connect(userId, meetingId));
    }

    @DeleteMapping("/meetings/{meetingId}/participants/me")
    public ResponseEntity<Void> leaveMeeting(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long meetingId
    ) {
        meetingParticipantService.leaveMeeting(userId, meetingId);
        return ResponseEntity.noContent().build();
    }
}
