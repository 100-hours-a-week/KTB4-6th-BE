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
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.transcript.entity.TranscriptSegment;
import com.backend.meety.domain.transcript.repository.TranscriptSegmentRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SummaryAiRequestFactoryTest {

    private final TranscriptSegmentRepository transcripts = mock(TranscriptSegmentRepository.class);
    private final SummaryAiRequestFactory factory = new SummaryAiRequestFactory(transcripts);

    @Test
    @DisplayName("화자 없이 전사 구간을 순서대로 조립한다")
    void buildsRequestWithoutSpeakers() {
        Team team = team();
        TeamMember member = member(team);
        Meeting meeting = meeting(team, member);
        meeting.start(LocalDateTime.of(2026, 9, 25, 14, 0));
        AiRequest aiRequest = withId(AiRequest.create(team, member, "key", AiRequestType.SUMMARY), 900L);
        TranscriptSegment withEnd = withId(TranscriptSegment.createFinal(
                meeting, "1:1", 1L, "첫 발언", 1000L, 3500L, LocalDateTime.now()), 101L);
        TranscriptSegment withoutEnd = withId(TranscriptSegment.createFinal(
                meeting, "1:2", 2L, "둘째 발언", 4000L, null, LocalDateTime.now()), 102L);
        when(transcripts.findAllByMeetingIdOrderBySequence(100L)).thenReturn(List.of(withEnd, withoutEnd));

        SummaryAiRequest request = factory.create(aiRequest, meeting);

        assertThat(request.requestId()).isEqualTo("900");
        assertThat(request.meetingId()).isEqualTo(100L);
        assertThat(request.title()).isEqualTo("회의");
        assertThat(request.purpose()).isEqualTo("테스트");
        assertThat(request.note()).isEqualTo("");
        assertThat(request.meetingStartedAt()).startsWith("2026-09-25T14:00");
        assertThat(request.speakers()).isEmpty();
        assertThat(request.segments()).hasSize(2);
        assertThat(request.segments().getFirst().segmentId()).isEqualTo(101L);
        assertThat(request.segments().getFirst().speakerId()).isZero();
        assertThat(request.segments().getFirst().endedAtMs()).isEqualTo(3500L);
        assertThat(request.segments().getLast().endedAtMs())
                .as("endedAtMs가 null이면 startedAtMs로 대체한다")
                .isEqualTo(4000L);
    }
}
