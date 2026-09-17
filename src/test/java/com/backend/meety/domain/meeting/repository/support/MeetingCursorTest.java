package com.backend.meety.domain.meeting.repository.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.backend.meety.domain.meeting.exception.MeetingErrorCode;
import com.backend.meety.domain.meeting.exception.MeetingException;
import java.time.LocalDate;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MeetingCursorTest {

    @Test
    @DisplayName("cursor를 문자열로 인코딩하고 복원한다")
    void encodeAndDecode() {
        MeetingCursor cursor = new MeetingCursor(LocalDate.of(2026, 9, 15));

        assertThat(MeetingCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    @DisplayName("cursor가 null 또는 blank이면 null로 처리한다")
    void decodeNullOrBlankCursor() {
        assertThat(MeetingCursor.decode(null)).isNull();
        assertThat(MeetingCursor.decode("   ")).isNull();
    }

    @Test
    @DisplayName("cursor 형식이 올바르지 않으면 INVALID_CURSOR 예외를 던진다")
    void decodeInvalidCursor() {
        String invalidCursor = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("invalid-date|100".getBytes());

        assertThatThrownBy(() -> MeetingCursor.decode(invalidCursor))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.INVALID_CURSOR);
    }
}
