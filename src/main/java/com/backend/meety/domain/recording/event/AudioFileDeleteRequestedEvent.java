package com.backend.meety.domain.recording.event;

public record AudioFileDeleteRequestedEvent(Long audioFileId, String storageKey) {
}
