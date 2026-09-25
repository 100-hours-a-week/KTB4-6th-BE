package com.backend.meety.domain.transcript.exception;

import com.backend.meety.global.exception.BaseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum TranscriptErrorCode implements BaseCode {

    INVALID_SPEAKER_MAPPING(HttpStatus.BAD_REQUEST, "INVALID_SPEAKER_MAPPING", "발화자 매핑 요청을 확인해주세요."),
    TRANSCRIPT_SPEAKER_NOT_FOUND(HttpStatus.NOT_FOUND, "TRANSCRIPT_SPEAKER_NOT_FOUND", "발화자를 찾을 수 없습니다."),
    TEAM_MEMBER_NOT_MEETING_PARTICIPANT(HttpStatus.CONFLICT,
            "TEAM_MEMBER_NOT_MEETING_PARTICIPANT", "회의에 참석한 팀원이 아닙니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
