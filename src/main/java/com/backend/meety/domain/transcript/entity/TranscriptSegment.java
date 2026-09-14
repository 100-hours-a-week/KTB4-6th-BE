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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "transcript_segments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TranscriptSegment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transcript_speakers_id", nullable = false)
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
}
