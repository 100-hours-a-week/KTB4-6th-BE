package com.backend.meety.domain.transcript.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.backend.meety.domain.meeting.exception.MeetingErrorCode;
import com.backend.meety.domain.meeting.exception.MeetingException;
import com.backend.meety.domain.transcript.dto.TranscriptSegmentListResponse;
import com.backend.meety.domain.transcript.dto.TranscriptSegmentResponse;
import com.backend.meety.domain.transcript.dto.TranscriptSpeakerMappingDetailResponse;
import com.backend.meety.domain.transcript.dto.TranscriptSpeakerMappingParticipantResponse;
import com.backend.meety.domain.transcript.dto.TranscriptSpeakerMappingRequest;
import com.backend.meety.domain.transcript.dto.TranscriptSpeakerMappingStateResponse;
import com.backend.meety.domain.transcript.dto.TranscriptSpeakerMappingType;
import com.backend.meety.domain.transcript.dto.TranscriptSpeakerResponse;
import com.backend.meety.domain.transcript.exception.TranscriptErrorCode;
import com.backend.meety.domain.transcript.exception.TranscriptException;
import com.backend.meety.domain.transcript.service.TranscriptService;
import com.backend.meety.global.exception.GlobalExceptionHandler;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TranscriptControllerTest {

    private TranscriptService transcriptService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        transcriptService = mock(TranscriptService.class);
        mvc = MockMvcBuilders.standaloneSetup(new TranscriptController(transcriptService))
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
    @DisplayName("전사 조회 API는 segments를 공통 성공 응답으로 반환한다")
    void getTranscriptsReturnsCommonSuccessResponse() throws Exception {
        // 테스트 목적:
        // keyword 없이 전사 조회를 요청하면 전체 전사 조회 결과가
        // segments 응답 객체로 반환되는지 검증한다.

        // given
        when(transcriptService.getTranscripts(1L, 100L, null))
                .thenReturn(new TranscriptSegmentListResponse(List.of(new TranscriptSegmentResponse(
                        900L,
                        "김진혁",
                        1L,
                        "hello",
                        1000L,
                        2000L,
                        LocalDateTime.of(2026, 9, 21, 5, 30)
                ))));

        // when & then
        mvc.perform(get("/api/v1/meetings/100/transcripts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.segments[0].segmentId").value(900L))
                .andExpect(jsonPath("$.data.segments[0].speakerDisplayName").value("김진혁"))
                .andExpect(jsonPath("$.data.segments[0].sequenceNumber").value(1L))
                .andExpect(jsonPath("$.data.segments[0].content").value("hello"))
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(transcriptService).getTranscripts(1L, 100L, null);
    }

    @Test
    @DisplayName("전사 조회 API는 keyword를 서비스로 전달한다")
    void getTranscriptsPassesKeywordToService() throws Exception {
        // 테스트 목적:
        // 전사 조회 요청에 keyword query parameter가 포함되면
        // Controller가 인증 사용자와 회의 ID, keyword를 Service에 전달하는지 검증한다.

        // given
        when(transcriptService.getTranscripts(1L, 100L, "카카오"))
                .thenReturn(new TranscriptSegmentListResponse(List.of()));

        // when & then
        mvc.perform(get("/api/v1/meetings/100/transcripts").param("keyword", "카카오"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.segments").isArray())
                .andExpect(jsonPath("$.data.segments").isEmpty())
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(transcriptService).getTranscripts(1L, 100L, "카카오");
    }

    @Test
    @DisplayName("존재하지 않는 회의의 전사 조회는 404를 반환한다")
    void getTranscriptsReturnsMeetingNotFound() throws Exception {
        // 테스트 목적:
        // 회의가 존재하지 않거나 삭제된 경우
        // 전사 조회 요청이 MEETING_NOT_FOUND로 거부되는지 검증한다.

        // given
        when(transcriptService.getTranscripts(1L, 404L, null))
                .thenThrow(new MeetingException(MeetingErrorCode.MEETING_NOT_FOUND));

        // when & then
        mvc.perform(get("/api/v1/meetings/404/transcripts"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("MEETING_NOT_FOUND"));
    }

    @Test
    @DisplayName("발화자 매핑 상세 조회 API는 segmentId와 인증 사용자를 서비스로 전달한다")
    void getSpeakerMappingPassesSegmentIdAndAuthenticatedUserToService() throws Exception {
        // 테스트 목적:
        // segment 기반 발화자 매핑 상세 조회 요청이 들어오면 Controller가 meetingId,
        // segmentId와 인증 사용자 ID를 Service에 전달하는지 검증한다.

        // given
        when(transcriptService.getSpeakerMapping(1L, 100L, 900L))
                .thenReturn(new TranscriptSpeakerMappingDetailResponse(
                        new TranscriptSpeakerMappingStateResponse(
                                50L,
                                "화자 1",
                                "김진혁",
                                TranscriptSpeakerMappingType.TEAM_MEMBER,
                                10L,
                                null
                        ),
                        List.of(new TranscriptSpeakerMappingParticipantResponse(10L, "김진혁"))
                ));

        // when & then
        mvc.perform(get("/api/v1/meetings/100/transcripts/900/speaker-mapping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.speaker.transcriptSpeakerId").value(50L))
                .andExpect(jsonPath("$.data.speaker.displayName").value("김진혁"))
                .andExpect(jsonPath("$.data.speaker.mappingType").value("TEAM_MEMBER"))
                .andExpect(jsonPath("$.data.participants[0].teamMemberId").value(10L))
                .andExpect(jsonPath("$.data.participants[0].nickname").value("김진혁"))
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(transcriptService).getSpeakerMapping(1L, 100L, 900L);
    }

    @Test
    @DisplayName("다른 회의의 segment 매핑 상세 조회는 404를 반환한다")
    void getSpeakerMappingReturnsTranscriptSegmentNotFound() throws Exception {
        // 테스트 목적:
        // segmentId가 존재하더라도 요청 meetingId의 segment가 아닌 경우
        // TRANSCRIPT_SEGMENT_NOT_FOUND로 거부되는지 검증한다.

        // given
        when(transcriptService.getSpeakerMapping(1L, 100L, 404L))
                .thenThrow(new TranscriptException(TranscriptErrorCode.TRANSCRIPT_SEGMENT_NOT_FOUND));

        // when & then
        mvc.perform(get("/api/v1/meetings/100/transcripts/404/speaker-mapping"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("TRANSCRIPT_SEGMENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("발화자 매핑 API는 path variable과 인증 사용자, request body를 서비스로 전달한다")
    void updateSpeakerMappingPassesPathVariablesAuthenticatedUserAndRequestBody() throws Exception {
        // 테스트 목적:
        // 발화자 매핑 수정 요청이 들어오면 Controller가 meetingId, transcriptSpeakerId,
        // 인증 사용자 ID와 요청 본문을 Service에 전달하는지 검증한다.

        // given
        when(transcriptService.updateSpeakerMapping(
                1L,
                100L,
                50L,
                new TranscriptSpeakerMappingRequest(10L, null)
        )).thenReturn(new TranscriptSpeakerResponse(50L, "화자 1", 10L, null));

        // when & then
        mvc.perform(put("/api/v1/meetings/100/speakers/50/mapping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "teamMemberId": 10,
                                  "customAlias": null
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.transcriptSpeakerId").value(50L))
                .andExpect(jsonPath("$.data.speakerLabel").value("화자 1"))
                .andExpect(jsonPath("$.data.mappedTeamMemberId").value(10L))
                .andExpect(jsonPath("$.data.customAlias").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(transcriptService).updateSpeakerMapping(
                1L,
                100L,
                50L,
                new TranscriptSpeakerMappingRequest(10L, null)
        );
    }

    @Test
    @DisplayName("잘못된 발화자 매핑 요청은 400을 반환한다")
    void updateSpeakerMappingReturnsInvalidSpeakerMapping() throws Exception {
        // 테스트 목적:
        // teamMemberId와 customAlias가 동시에 입력된 경우
        // 발화자 매핑 요청이 INVALID_SPEAKER_MAPPING으로 거부되는지 검증한다.

        // given
        when(transcriptService.updateSpeakerMapping(
                1L,
                100L,
                50L,
                new TranscriptSpeakerMappingRequest(10L, "외부참석자")
        )).thenThrow(new TranscriptException(TranscriptErrorCode.INVALID_SPEAKER_MAPPING));

        // when & then
        mvc.perform(put("/api/v1/meetings/100/speakers/50/mapping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "teamMemberId": 10,
                                  "customAlias": "외부참석자"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("INVALID_SPEAKER_MAPPING"));
    }
}
