package com.backend.meety.domain.ai.client;

import static com.backend.meety.domain.recording.RecordingFixtures.meeting;
import static com.backend.meety.domain.recording.RecordingFixtures.member;
import static com.backend.meety.domain.recording.RecordingFixtures.team;
import static com.backend.meety.domain.recording.RecordingFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingSummary;
import com.backend.meety.domain.meeting.service.SummaryAiRequestFactory;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.transcript.entity.TranscriptSegment;
import com.backend.meety.domain.transcript.entity.TranscriptSpeaker;
import com.backend.meety.domain.transcript.repository.TranscriptSegmentRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class RestSummaryAiClientTest {

    @Test
    @DisplayName("저장된 AI 화자 ID와 미확인 null이 요약 HTTP 요청까지 보존된다")
    void sendsStoredSpeakerIdsInSummaryRequest() {
        // 저장된 전사 → 요약 요청 조립 → HTTP JSON 전송에서 화자 구분이 사라지지 않는지 검증한다.
        Team team = team();
        TeamMember member = member(team);
        Meeting meeting = meeting(team, member);
        LocalDateTime startedAt = LocalDateTime.of(2026, 9, 25, 14, 0);
        meeting.start(startedAt);
        AiRequest aiRequest = withId(AiRequest.create(team, member, "key", AiRequestType.SUMMARY), 900L);
        MeetingSummary summary = MeetingSummary.createPending(aiRequest, team, meeting, 1L, null);
        TranscriptSegmentRepository transcripts = mock(TranscriptSegmentRepository.class);
        TranscriptSpeaker mappedSpeaker = TranscriptSpeaker.create(meeting, "화자 1");
        mappedSpeaker.mapToTeamMember(member);
        TranscriptSegment mappedSegment = withId(TranscriptSegment.createFinal(
                meeting, "1:1", 1L, "첫 화자 발언", 1000L, 2000L, startedAt, 0L), 101L);
        mappedSegment.assignSpeaker(mappedSpeaker);
        when(transcripts.findAllByMeetingIdOrderBySequence(meeting.getId())).thenReturn(List.of(
                mappedSegment,
                withId(TranscriptSegment.createFinal(
                        meeting, "1:2", 2L, "다른 화자 발언", 2000L, 3000L, startedAt, 1L), 102L),
                withId(TranscriptSegment.createFinal(
                        meeting, "1:3", 3L, "세 번째 화자 발언", 3000L, 4000L, startedAt, 2L), 103L),
                withId(TranscriptSegment.createFinal(
                        meeting, "1:4", 4L, "화자 미확인 발언", 4000L, 5000L, startedAt, null), 104L)
        ));
        SummaryAiRequest request = new SummaryAiRequestFactory(transcripts).create(aiRequest, summary);
        RestClient.Builder builder = RestClient.builder().baseUrl("https://ai.example");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestSummaryAiClient client = new RestSummaryAiClient(builder.build());
        server.expect(requestTo("https://ai.example/v1/summary"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(httpRequest -> {
                    JsonNode body = new ObjectMapper().readTree(
                            ((MockClientHttpRequest) httpRequest).getBodyAsString());
                    JsonNode segments = body.path("segments");
                    assertThat(segments.size()).isEqualTo(4);
                    assertThat(segments.get(0).path("speakerId").isIntegralNumber()).isTrue();
                    assertThat(segments.get(0).path("speakerId").asLong()).isZero();
                    assertThat(segments.get(0).has("displayName")).isTrue();
                    assertThat(segments.get(0).path("displayName").asText()).isEqualTo(member.getDisplayName());
                    assertThat(segments.get(1).path("speakerId").asLong()).isEqualTo(1L);
                    assertThat(segments.get(2).path("speakerId").asLong()).isEqualTo(2L);
                    assertThat(segments.get(3).has("speakerId")).isTrue();
                    assertThat(segments.get(3).path("speakerId").isNull()).isTrue();
                    assertThat(segments.get(3).has("displayName")).isTrue();
                    assertThat(segments.get(3).path("displayName").isNull()).isTrue();
                    assertThat(body.path("speakers").isArray()).isTrue();
                    assertThat(body.path("speakers").size()).isZero();
                })
                .andRespond(withSuccess("# 회의 요약", MediaType.valueOf("text/markdown;charset=UTF-8")));

        String result = client.requestSummary(request);

        server.verify();
        assertThat(result).isEqualTo("# 회의 요약");
    }
}
