package com.backend.meety.domain.transcript.repository;

import com.backend.meety.domain.transcript.entity.TranscriptSegment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TranscriptSegmentRepository extends JpaRepository<TranscriptSegment, Long> {

    boolean existsBySourceSegmentKey(String sourceSegmentKey);
}
