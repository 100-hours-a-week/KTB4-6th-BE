package com.backend.meety.domain.recording.exception;

import com.backend.meety.global.exception.BaseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum RecordingErrorCode implements BaseCode {

    RECORDING_ALREADY_ACTIVE(HttpStatus.CONFLICT, "RECORDING_ALREADY_ACTIVE", "이미 녹음 중이거나 진행 중인 회의입니다."),
    RECORDING_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "RECORDING_SESSION_NOT_FOUND", "녹음 세션을 찾을 수 없습니다."),
    INVALID_RECORDING_STATUS(HttpStatus.BAD_REQUEST, "INVALID_RECORDING_STATUS", "지원하지 않는 녹음 상태입니다."),
    UNSUPPORTED_AUDIO_FORMAT(HttpStatus.BAD_REQUEST, "UNSUPPORTED_AUDIO_FORMAT", "지원하지 않는 오디오 형식입니다."),
    RECORDING_OWNER_REQUIRED(HttpStatus.FORBIDDEN, "RECORDING_OWNER_REQUIRED", "녹음을 시작한 활성 팀원만 상태를 변경할 수 있습니다."),
    INVALID_RECORDING_STATUS_TRANSITION(HttpStatus.CONFLICT, "INVALID_RECORDING_STATUS_TRANSITION", "현재 상태에서는 요청한 녹음 상태로 변경할 수 없습니다."),
    RECORDING_AUTO_END_REACHED(HttpStatus.CONFLICT, "RECORDING_AUTO_END_REACHED", "녹음 최대 시간이 지나 재개할 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
