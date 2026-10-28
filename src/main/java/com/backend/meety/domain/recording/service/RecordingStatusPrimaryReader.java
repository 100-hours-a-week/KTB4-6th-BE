package com.backend.meety.domain.recording.service;

import com.backend.meety.domain.recording.repository.RecordingSessionRepository;
import com.backend.meety.domain.recording.repository.RecordingStatusSnapshot;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecordingStatusPrimaryReader {

    private final RecordingSessionRepository recordings;

    // The routing DataSource sends a non-read-only transaction to the write/primary pool.
    // Return detached scalar values; no transaction or row lock is held during AI I/O.
    @Transactional
    public List<RecordingStatusSnapshot> findStatuses(Collection<Long> sessionIds) {
        return recordings.findStatusSnapshotsByIdIn(sessionIds);
    }
}
