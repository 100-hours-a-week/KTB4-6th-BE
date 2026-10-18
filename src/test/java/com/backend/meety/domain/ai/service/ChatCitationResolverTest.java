package com.backend.meety.domain.ai.service;

import static com.backend.meety.domain.recording.RecordingFixtures.NOW;
import static com.backend.meety.domain.recording.RecordingFixtures.meeting;
import static com.backend.meety.domain.recording.RecordingFixtures.member;
import static com.backend.meety.domain.recording.RecordingFixtures.team;
import static com.backend.meety.domain.recording.RecordingFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.client.ChatAiCitation;
import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingSummary;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.meeting.repository.MeetingSummaryRepository;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.transcript.entity.TranscriptSegment;
import com.backend.meety.domain.transcript.repository.TranscriptSegmentRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ChatCitationResolverTest {

    private final MeetingRepository meetings = mock(MeetingRepository.class);
    private final TranscriptSegmentRepository transcripts = mock(TranscriptSegmentRepository.class);
    private final MeetingSummaryRepository summaries = mock(MeetingSummaryRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatCitationResolver resolver = new ChatCitationResolver(meetings, transcripts, summaries, objectMapper);

    private Meeting meeting;
    private Meeting otherMeeting;
    private TranscriptSegment segment;
    private MeetingSummary summary;

    @BeforeEach
    void setUp() {
        Team team = team();
        TeamMember member = member(team);
        meeting = meeting(team, member);
        meeting.start(NOW);
        otherMeeting = withId(Meeting.create(team, member, "다른 회의", "테스트", null, NOW, 30), 101L);
        segment = withId(TranscriptSegment.createFinal(meeting, "seg-1", 8L, "배포는 금요일", 754000L, 757000L, NOW), 801L);
        summary = withId(MeetingSummary.createPending(
                withId(AiRequest.create(team, member, "s", AiRequestType.SUMMARY), 5L), team, meeting, 1L, null), 12L);

        when(meetings.findByIdAndDeletedAtIsNull(100L)).thenReturn(Optional.of(meeting));
        when(meetings.findByIdAndDeletedAtIsNull(101L)).thenReturn(Optional.of(otherMeeting));
        when(transcripts.findById(801L)).thenReturn(Optional.of(segment));
        when(summaries.findById(12L)).thenReturn(Optional.of(summary));
    }

    @Test
    @DisplayName("전사 근거는 회의 제목·시작 시각·문장 시작 위치를, 요약 근거는 회의 제목·시작 시각을 채운다")
    void fillsDisplayFields() {
        JsonNode result = resolve(transcript(100L, 801L), summary(100L, 12L));

        assertThat(result).hasSize(2);
        JsonNode transcript = result.get(0);
        assertThat(transcript.get("sourceType").asString()).isEqualTo("transcript");
        assertThat(transcript.get("segmentId").asLong()).isEqualTo(801L);
        assertThat(transcript.get("meetingTitle").asString()).isEqualTo("회의");
        assertThat(transcript.get("meetingStartedAt").asString()).isEqualTo("2026-09-18T14:20:00");
        assertThat(transcript.get("startedAtMs").asLong()).isEqualTo(754000L);
        assertThat(transcript.has("summaryId")).isFalse();

        JsonNode summaryCitation = result.get(1);
        assertThat(summaryCitation.get("sourceType").asString()).isEqualTo("summary");
        assertThat(summaryCitation.get("summaryId").asLong()).isEqualTo(12L);
        assertThat(summaryCitation.get("meetingTitle").asString()).isEqualTo("회의");
        assertThat(summaryCitation.has("startedAtMs")).isFalse();
    }

    @Test
    @DisplayName("같은 근거가 여러 번 오면 하나로 합친다")
    void mergesDuplicates() {
        assertThat(resolve(transcript(100L, 801L), transcript(100L, 801L))).hasSize(1);
    }

    @Test
    @DisplayName("sourceType이 잘못되었거나 필요한 ID가 비면 제외한다")
    void dropsInvalidFormat() {
        assertThat(resolve(
                new ChatAiCitation("note", 100L, 801L, null),
                new ChatAiCitation("transcript", 100L, null, null),
                new ChatAiCitation("summary", 100L, null, null),
                new ChatAiCitation("transcript", null, 801L, null))).isEmpty();
    }

    @Test
    @DisplayName("회의가 없거나 다른 팀 회의면 제외한다")
    void dropsMissingOrOtherTeamMeeting() {
        Team otherTeam = withId(Team.create("다른 팀"), 3L);
        Meeting otherTeamMeeting = withId(
                Meeting.create(otherTeam, member(otherTeam), "남의 회의", "테스트", null, NOW, 30), 300L);
        when(meetings.findByIdAndDeletedAtIsNull(300L)).thenReturn(Optional.of(otherTeamMeeting));

        assertThat(resolve(transcript(999L, 801L), transcript(300L, 801L))).isEmpty();
    }

    @Test
    @DisplayName("전사 문장·요약이 없거나 근거의 회의 소속이 아니면 제외한다")
    void dropsMissingOrMismatchedSource() {
        assertThat(resolve(
                transcript(100L, 999L),
                summary(100L, 999L),
                transcript(101L, 801L),
                summary(101L, 12L))).isEmpty();
    }

    @Test
    @DisplayName("근거가 없으면 빈 배열로 저장한다")
    void storesEmptyArrayWithoutCitations() {
        assertThat(resolver.resolve(2L, 900L, null)).isEqualTo("[]");
        assertThat(resolver.resolve(2L, 900L, List.of())).isEqualTo("[]");
    }

    private JsonNode resolve(ChatAiCitation... citations) {
        return objectMapper.readTree(resolver.resolve(2L, 900L, Arrays.asList(citations)));
    }

    private ChatAiCitation transcript(Long meetingId, Long segmentId) {
        return new ChatAiCitation("transcript", meetingId, segmentId, null);
    }

    private ChatAiCitation summary(Long meetingId, Long summaryId) {
        return new ChatAiCitation("summary", meetingId, null, summaryId);
    }
}
