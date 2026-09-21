package com.backend.meety.domain.transcript.entity;

import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "transcript_segments",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_transcript_segments_meeting_sequence",
                columnNames = {"meeting_id", "sequence_number"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TranscriptSegment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transcript_speakers_id")
    private TranscriptSpeaker transcriptSpeaker;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meeting_id", nullable = false)
    private Meeting meeting;

    @Column(name = "sequence_number", nullable = false)
    private Long sequenceNumber;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "started_at_ms", nullable = false)
    private Long startedAtMs;

    @Column(name = "ended_at_ms")
    private Long endedAtMs;

    @Column(name = "recognized_at", nullable = false)
    private LocalDateTime recognizedAt;

    public static TranscriptSegment committed(
            Meeting meeting,
            Long sequenceNumber,
            String content,
            Long startedAtMs,
            Long endedAtMs,
            LocalDateTime recognizedAt
    ) {
        TranscriptSegment segment = new TranscriptSegment();
        segment.meeting = meeting;
        segment.sequenceNumber = sequenceNumber;
        segment.content = content;
        segment.startedAtMs = startedAtMs;
        segment.endedAtMs = endedAtMs;
        segment.recognizedAt = recognizedAt;
        return segment;
    }
}
