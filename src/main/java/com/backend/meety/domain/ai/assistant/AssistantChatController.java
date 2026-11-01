package com.backend.meety.domain.ai.assistant;

import com.backend.meety.domain.ai.assistant.dto.AssistantChatCreateRequest;
import com.backend.meety.domain.ai.assistant.dto.AssistantChatPageResponse;
import com.backend.meety.domain.ai.assistant.dto.AssistantChatTurnResponse;
import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.meeting.realtime.SseHeaders;
import com.backend.meety.global.response.ApiResponse;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/assistant/chat")
public class AssistantChatController {

    private final AssistantChatService assistantChatService;
    private final AssistantChatDispatcher dispatcher;
    private final AssistantChatSseEmitterRegistry emitters;
    private final AssistantChatProperties properties;

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> ask(
            @AuthenticationPrincipal Long userId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody AssistantChatCreateRequest request
    ) {
        AssistantChatSubmission submission = assistantChatService.submit(
                userId, request.teamId(), idempotencyKey, request.question());
        SseEmitter emitter = emitters.register(submission.requestId(), properties.streamTimeout().toMillis() + 5_000);
        AssistantChatTurnResponse current = assistantChatService.getRequest(userId, submission.requestId());
        emitters.send(submission.requestId(), "status",
                Map.of("aiRequestId", submission.requestId(), "status", current.status().name()));
        replayIfTerminal(submission.requestId(), current);
        if (current.status() == AiRequestStatus.ACCEPTED) dispatcher.dispatch(submission.requestId());
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noCache())
                .header(HttpHeaders.CONNECTION, "keep-alive")
                .header(SseHeaders.X_ACCEL_BUFFERING, SseHeaders.X_ACCEL_BUFFERING_OFF)
                .header("X-Ai-Request-Id", submission.requestId().toString())
                .body(emitter);
    }

    @GetMapping(value = "/requests/{requestId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> reconnect(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long requestId
    ) {
        assistantChatService.getRequest(userId, requestId);
        SseEmitter emitter = emitters.register(requestId, properties.streamTimeout().toMillis() + 5_000);
        AssistantChatTurnResponse refreshed = assistantChatService.getRequest(userId, requestId);
        emitters.send(requestId, "status", Map.of("aiRequestId", requestId, "status", refreshed.status().name()));
        replayIfTerminal(requestId, refreshed);
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).cacheControl(CacheControl.noCache())
                .header(HttpHeaders.CONNECTION, "keep-alive")
                .header(SseHeaders.X_ACCEL_BUFFERING, SseHeaders.X_ACCEL_BUFFERING_OFF).body(emitter);
    }

    @GetMapping("/requests/{requestId}")
    public ResponseEntity<ApiResponse<AssistantChatTurnResponse>> getRequest(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long requestId
    ) {
        return ResponseEntity.ok(ApiResponse.success(assistantChatService.getRequest(userId, requestId)));
    }

    @GetMapping("/teams/{teamId}/messages")
    public ResponseEntity<ApiResponse<AssistantChatPageResponse>> getMessages(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long teamId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                assistantChatService.getMessages(userId, teamId, cursor, size)));
    }

    private void replayIfTerminal(Long requestId, AssistantChatTurnResponse response) {
        if (response.status() == AiRequestStatus.COMPLETED) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("aiRequestId", requestId);
            payload.put("answer", response.answer());
            payload.put("referencedMeetingIds", response.referencedMeetingIds());
            if (response.meetingDraft() != null) payload.put("meetingDraft", response.meetingDraft());
            emitters.complete(requestId, "completed", payload);
        } else if (response.status() == AiRequestStatus.FAILED) {
            emitters.complete(requestId, "failed", Map.of("aiRequestId", requestId,
                    "code", response.failureCode() == null ? "generation_failed" : response.failureCode(),
                    "message", "답변을 생성하지 못했습니다. 다시 시도해주세요."));
        }
    }
}
