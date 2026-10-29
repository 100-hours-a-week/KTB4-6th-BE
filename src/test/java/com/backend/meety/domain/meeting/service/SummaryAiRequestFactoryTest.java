package com.backend.meety.domain.meeting.service;

import static com.backend.meety.domain.recording.RecordingFixtures.meeting;
import static com.backend.meety.domain.recording.RecordingFixtures.member;
import static com.backend.meety.domain.recording.RecordingFixtures.team;
import static com.backend.meety.domain.recording.RecordingFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.client.SummaryAiRequest;
import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingSummary;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.transcript.entity.TranscriptSegment;
import com.backend.meety.domain.transcript.entity.TranscriptSpeaker;
import com.backend.meety.domain.transcript.repository.TranscriptSegmentRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SummaryAiRequestFactoryTest {

    private final TranscriptSegmentRepository transcripts = mock(TranscriptSegmentRepository.class);
    private final SummaryAiRequestFactory factory = new SummaryAiRequestFactory(transcripts);

    @Test
    @DisplayName("화자 정보가 없는 전사도 순서와 요약 재생성 정보를 유지한다")
    void buildsRequestWithoutSpeakers() {
        Team team = team();
        TeamMember member = member(team);
        Meeting meeting = meeting(team, member);
        meeting.start(LocalDateTime.of(2026, 9, 25, 14, 0));
        AiRequest aiRequest = withId(AiRequest.create(team, member, "key", AiRequestType.SUMMARY), 900L);
        TranscriptSegment withEnd = withId(TranscriptSegment.createFinal(
                meeting, "1:1", 1L, "첫 발언", 1000L, 3500L, LocalDateTime.now(), null), 101L);
        TranscriptSegment withoutEnd = withId(TranscriptSegment.createFinal(
                meeting, "1:2", 2L, "둘째 발언", 4000L, null, LocalDateTime.now(), null), 102L);
        when(transcripts.findAllByMeetingIdOrderBySequence(100L)).thenReturn(List.of(withEnd, withoutEnd));
        MeetingSummary summary = MeetingSummary.createPending(aiRequest, team, meeting, 2L, "다시 요약해줘");

        SummaryAiRequest request = factory.create(aiRequest, summary);

        assertThat(request.requestId()).isEqualTo("900");
        assertThat(request.meetingId()).isEqualTo(100L);
        assertThat(request.title()).isEqualTo("회의");
        assertThat(request.purpose()).isEqualTo("테스트");
        assertThat(request.note()).isEqualTo("");
        assertThat(request.regenerationReason()).isEqualTo("다시 요약해줘");
        assertThat(request.meetingStartedAt()).startsWith("2026-09-25T14:00");
        assertThat(request.speakers()).isEmpty();
        assertThat(request.segments()).hasSize(2);
        assertThat(request.segments().getFirst().segmentId()).isEqualTo(101L);
        assertThat(request.segments()).allSatisfy(segment -> assertThat(segment.speakerId()).isNull());
        assertThat(request.segments()).allSatisfy(segment -> assertThat(segment.displayName()).isNull());
        assertThat(request.segments().getFirst().endedAtMs()).isEqualTo(3500L);
        assertThat(request.segments().getLast().endedAtMs())
                .as("endedAtMs가 null이면 startedAtMs로 대체한다")
                .isEqualTo(4000L);
    }

    @Test
    @DisplayName("요약 입력은 세그먼트별 현재 매핑 이름을 전달하고 미확정 화자는 null을 유지한다")
    void includesCurrentSpeakerDisplayNameAndPreservesUnknownSpeaker() {
        Team team = team();
        TeamMember member = member(team);
        Meeting meeting = meeting(team, member);
        meeting.start(LocalDateTime.of(2026, 9, 25, 14, 0));
        AiRequest aiRequest = withId(AiRequest.create(team, member, "key", AiRequestType.SUMMARY), 900L);
        TranscriptSegment first = withId(TranscriptSegment.createFinal(
                meeting, "1:1", 1L, "첫 화자 발언", 1000L, 2000L, LocalDateTime.now(), 0L), 101L);
        TranscriptSegment second = withId(TranscriptSegment.createFinal(
                meeting, "1:2", 2L, "다른 화자 발언", 2000L, 3000L, LocalDateTime.now(), 1L), 102L);
        TranscriptSegment third = withId(TranscriptSegment.createFinal(
                meeting, "1:3", 3L, "세 번째 화자 발언", 3000L, 4000L, LocalDateTime.now(), 2L), 103L);
        TranscriptSegment unknown = withId(TranscriptSegment.createFinal(
                meeting, "1:4", 4L, "화자 미확인 발언", 4000L, 5000L, LocalDateTime.now(), null), 104L);
        TranscriptSpeaker mappedSpeaker = withId(TranscriptSpeaker.create(meeting, "화자 1"), 301L);
        mappedSpeaker.mapToTeamMember(member);
        first.assignSpeaker(mappedSpeaker);
        unknown.assignSpeaker(mappedSpeaker);
        when(transcripts.findAllByMeetingIdOrderBySequence(100L))
                .thenReturn(List.of(first, second, third, unknown));
        MeetingSummary summary = MeetingSummary.createPending(aiRequest, team, meeting, 1L, null);

        SummaryAiRequest request = factory.create(aiRequest, summary);

        assertThat(request.segments()).extracting(segment -> segment.speakerId())
                .containsExactly(0L, 1L, 2L, null);
        assertThat(request.segments()).extracting(segment -> segment.segmentId())
                .containsExactly(101L, 102L, 103L, 104L);
        assertThat(request.segments()).extracting(segment -> segment.displayName())
                .containsExactly(member.getDisplayName(), null, null, null);
        assertThat(request.speakers()).isEmpty();
    }

    @Test
    @DisplayName("재생성 사유가 없으면 null로 전달한다")
    void buildsRequestWithoutReason() {
        Team team = team();
        TeamMember member = member(team);
        Meeting meeting = meeting(team, member);
        meeting.start(LocalDateTime.of(2026, 9, 25, 14, 0));
        AiRequest aiRequest = withId(AiRequest.create(team, member, "key", AiRequestType.SUMMARY), 900L);
        when(transcripts.findAllByMeetingIdOrderBySequence(100L)).thenReturn(List.of());
        MeetingSummary summary = MeetingSummary.createPending(aiRequest, team, meeting, 1L, null);

        SummaryAiRequest request = factory.create(aiRequest, summary);

        assertThat(request.regenerationReason()).isNull();
    }
}
