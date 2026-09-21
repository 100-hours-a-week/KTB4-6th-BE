package com.backend.meety.domain.recording.realtime;

import com.backend.meety.domain.ai.realtime.AiLiveMeetingConnection;
import com.backend.meety.domain.ai.realtime.AiLiveMeetingRegistry;
import com.backend.meety.domain.meeting.realtime.MeetingEventBroadcaster;
import com.backend.meety.domain.meeting.realtime.MeetingEventType;
import com.backend.meety.domain.meeting.realtime.MeetingRealtimeEvent;
import com.backend.meety.domain.recording.entity.RecordingSessionStatus;
import com.backend.meety.domain.recording.event.RecordingLifecycleEvent;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class RecordingRealtimeEventListener {

    private final AiLiveMeetingRegistry aiLiveMeetingRegistry;
    private final AudioWebSocketRegistry audioWebSocketRegistry;
    private final RecordingTimeoutScheduler recordingTimeoutScheduler;
    private final MeetingEventBroadcaster meetingEventBroadcaster;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(RecordingLifecycleEvent event) {
        switch (event.type()) {
            case STARTED -> recordingTimeoutScheduler.scheduleMaxDurationTimeout(event.recordingSessionId());
            case PAUSED -> handlePaused(event);
            case RESUMED -> handleResumed(event);
            case COMPLETED -> handleCompleted(event);
        }
    }

    private void handlePaused(RecordingLifecycleEvent event) {
        recordingTimeoutScheduler.schedulePauseTimeout(event.recordingSessionId());
        aiLiveMeetingRegistry.find(event.recordingSessionId()).ifPresent(connection -> {
            try {
                connection.sendPause();
            } catch (IOException | IllegalStateException e) {
                log.warn("AI session.pause 전송에 실패했습니다. recordingSessionId={}",
                        event.recordingSessionId(), e);
            }
        });
    }

    private void handleResumed(RecordingLifecycleEvent event) {
        recordingTimeoutScheduler.cancelPauseTimeout(event.recordingSessionId());
        aiLiveMeetingRegistry.find(event.recordingSessionId()).ifPresent(connection -> {
            try {
                connection.sendResume();
            } catch (IOException | IllegalStateException e) {
                log.warn("AI session.resume 전송에 실패했습니다. recordingSessionId={}",
                        event.recordingSessionId(), e);
            }
        });
    }

    private void handleCompleted(RecordingLifecycleEvent event) {
        recordingTimeoutScheduler.cancelAll(event.recordingSessionId());
        audioWebSocketRegistry.close(event.recordingSessionId());
        AiLiveMeetingConnection connection = aiLiveMeetingRegistry.find(event.recordingSessionId()).orElse(null);
        if (connection == null) {
            meetingEventBroadcaster.broadcastAndComplete(MeetingRealtimeEvent.recording(
                    MeetingEventType.MEETING_COMPLETED,
                    event.meetingId(),
                    event.recordingSessionId(),
                    RecordingSessionStatus.COMPLETED
            ));
            return;
        }
        try {
            connection.sendStop();
        } catch (IOException | IllegalStateException e) {
            log.warn("AI session.stop 전송에 실패했습니다. recordingSessionId={}",
                    event.recordingSessionId(), e);
            connection.close();
            aiLiveMeetingRegistry.remove(event.recordingSessionId());
            meetingEventBroadcaster.broadcastAndComplete(MeetingRealtimeEvent.recording(
                    MeetingEventType.MEETING_COMPLETED,
                    event.meetingId(),
                    event.recordingSessionId(),
                    RecordingSessionStatus.COMPLETED
            ));
        }
    }
}
