package com.backend.meety.domain.recording.exception;

import com.backend.meety.global.exception.BaseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AudioFileErrorCode implements BaseCode {

    INVALID_AUDIO_CONTENT_TYPE(HttpStatus.BAD_REQUEST, "INVALID_AUDIO_CONTENT_TYPE", "지원하지 않는 음성 형식입니다."),
    AUDIO_FILE_CREATE_FORBIDDEN(HttpStatus.FORBIDDEN, "AUDIO_FILE_CREATE_FORBIDDEN", "음성 파일을 생성할 권한이 없습니다."),
    AUDIO_FILE_ALREADY_EXISTS(HttpStatus.CONFLICT, "AUDIO_FILE_ALREADY_EXISTS", "이미 생성된 음성 파일이 있습니다."),
    AUDIO_UPLOAD_URL_CREATE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "AUDIO_UPLOAD_URL_CREATE_FAILED", "음성 업로드 준비에 실패했습니다."),
    AUDIO_FILE_ACCESS_DENIED(HttpStatus.FORBIDDEN, "AUDIO_FILE_ACCESS_DENIED", "음성 파일에 접근할 권한이 없습니다."),
    AUDIO_FILE_NOT_FOUND(HttpStatus.NOT_FOUND, "AUDIO_FILE_NOT_FOUND", "음성 파일을 찾을 수 없습니다."),
    AUDIO_FILE_NOT_UPLOADING(HttpStatus.CONFLICT, "AUDIO_FILE_NOT_UPLOADING", "업로드 중인 파일이 아닙니다."),
    AUDIO_OBJECT_NOT_FOUND(HttpStatus.CONFLICT, "AUDIO_OBJECT_NOT_FOUND", "업로드된 음성 파일을 확인할 수 없습니다."),
    AUDIO_FILE_NOT_AVAILABLE(HttpStatus.CONFLICT, "AUDIO_FILE_NOT_AVAILABLE", "아직 사용할 수 없는 음성 파일입니다."),
    AUDIO_FILE_EXPIRED(HttpStatus.GONE, "AUDIO_FILE_EXPIRED", "보관 기간이 만료된 음성 파일입니다."),
    AUDIO_DOWNLOAD_URL_CREATE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "AUDIO_DOWNLOAD_URL_CREATE_FAILED", "다운로드 URL 생성에 실패했습니다."),
    AUDIO_FILE_DELETE_IN_PROGRESS(HttpStatus.CONFLICT, "AUDIO_FILE_DELETE_IN_PROGRESS", "음성 파일 삭제가 이미 진행 중입니다."),
    AUDIO_FILE_DELETE_REQUEST_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "AUDIO_FILE_DELETE_REQUEST_FAILED", "음성 파일 삭제 요청에 실패했습니다."),
    AUDIO_FILE_UPDATE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "AUDIO_FILE_UPDATE_FAILED", "음성 파일 상태 변경에 실패했습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
