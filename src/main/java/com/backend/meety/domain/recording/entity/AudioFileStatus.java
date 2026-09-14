package com.backend.meety.domain.recording.entity;

public enum AudioFileStatus {
    UPLOADING,
    AVAILABLE,
    UPLOAD_FAILED,
    DELETE_PENDING,
    DELETED,
    DELETE_FAILED
}
