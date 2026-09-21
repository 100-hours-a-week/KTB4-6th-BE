package com.backend.meety.domain.meeting.controller;

import com.backend.meety.domain.meeting.realtime.MeetingSseAccessService;
import com.backend.meety.domain.meeting.realtime.MeetingSseRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class MeetingEventController {

    private final MeetingSseAccessService meetingSseAccessService;
    private final MeetingSseRegistry meetingSseRegistry;

    @GetMapping(value = "/meetings/{meetingId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connect(@AuthenticationPrincipal Long userId, @PathVariable Long meetingId) {
        meetingSseAccessService.validateCanConnect(userId, meetingId);
        return meetingSseRegistry.register(meetingId);
    }
}
