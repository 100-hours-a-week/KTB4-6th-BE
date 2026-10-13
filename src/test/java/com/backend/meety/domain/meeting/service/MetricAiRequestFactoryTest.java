package com.backend.meety.domain.meeting.service;

import static com.backend.meety.domain.recording.RecordingFixtures.meeting;
import static com.backend.meety.domain.recording.RecordingFixtures.member;
import static com.backend.meety.domain.recording.RecordingFixtures.team;
import static com.backend.meety.domain.recording.RecordingFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.client.MetricAiRequest;
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

class MetricAiRequestFactoryTest {

    private final TranscriptSegmentRepository transcripts = mock(TranscriptSegmentRepository.class);
    private final MetricAiRequestFactory factory = new MetricAiRequestFactory(transcripts);

    @Test
    @DisplayName("완료된 요약 본문과 전체 전사를 순서대로 Metric 요청에 담는다")
    void buildsMetricRequestWithSummaryAndOrderedSegments() {
        // 테스트 목적:
        // Metric AI가 분석해야 하는 원본 요약 버전의 본문과
        // 확정 전사 전체가 sequenceNumber 오름차순 그대로 전달되는지 검증한다.

        // given
        Team team = team();
        TeamMember member = member(team);
        Meeting meeting = meeting(team, member);
        AiRequest summaryRequest = withId(AiRequest.create(team, member, "summary-key", AiRequestType.SUMMARY), 800L);
        AiRequest metricRequest = withId(AiRequest.create(team, member, "metric-key", AiRequestType.METRIC), 900L);
        MeetingSummary summary = MeetingSummary.createPending(summaryRequest, team, meeting, 2L, "다시");
        summary.complete("# 회의 요약");
        TranscriptSpeaker speaker = withId(TranscriptSpeaker.create(meeting, "화자 1"), 301L);
        TranscriptSegment first = withId(TranscriptSegment.createFinal(
                meeting, "1:1", 0L, "첫 발언", 1000L, 3500L, LocalDateTime.now()), 101L);
        first.assignSpeaker(speaker);
        TranscriptSegment second = withId(TranscriptSegment.createFinal(
                meeting, "1:2", 1L, "둘째 발언", 4000L, null, LocalDateTime.now()), 102L);
        when(transcripts.findAllByMeetingIdOrderBySequence(100L)).thenReturn(List.of(first, second));

        // when
        MetricAiRequest result = factory.create(metricRequest, summary);

        // then
        assertThat(result.requestId()).isEqualTo("900");
        assertThat(result.meetingId()).isEqualTo(100L);
        assertThat(result.summary()).isEqualTo("# 회의 요약");
        assertThat(result.segments()).hasSize(2);
        assertThat(result.segments().getFirst().segmentId()).isEqualTo(101L);
        assertThat(result.segments().getFirst().speakerId()).isEqualTo(301L);
        assertThat(result.segments().getFirst().sequenceNumber()).isZero();
        assertThat(result.segments().getFirst().startedAtMs()).isEqualTo(1000L);
        assertThat(result.segments().getFirst().endedAtMs()).isEqualTo(3500L);
        assertThat(result.segments().getLast().speakerId()).isZero();
        assertThat(result.segments().getLast().endedAtMs()).isEqualTo(4000L);
    }
}
