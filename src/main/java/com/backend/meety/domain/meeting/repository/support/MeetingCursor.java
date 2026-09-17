package com.backend.meety.domain.meeting.repository.support;

import com.backend.meety.domain.meeting.exception.MeetingErrorCode;
import com.backend.meety.domain.meeting.exception.MeetingException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;

public record MeetingCursor(
        LocalDate date
) {

    public static MeetingCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }

        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            String decodedCursor = new String(decoded, StandardCharsets.UTF_8);
            return new MeetingCursor(LocalDate.parse(decodedCursor));
        } catch (MeetingException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new MeetingException(MeetingErrorCode.INVALID_CURSOR);
        } catch (RuntimeException e) {
            throw new MeetingException(MeetingErrorCode.INVALID_CURSOR);
        }
    }

    public String encode() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(date.toString().getBytes(StandardCharsets.UTF_8));
    }
}
