package com.backend.meety.domain.meeting.repository.support;

import com.backend.meety.domain.meeting.exception.MeetingErrorCode;
import com.backend.meety.domain.meeting.exception.MeetingException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

public record MeetingCursor(
        LocalDateTime effectiveStartAt,
        Long meetingId
) {

    private static final String DELIMITER = "|";

    public static MeetingCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }

        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            String decodedCursor = new String(decoded, StandardCharsets.UTF_8);
            String[] values = decodedCursor.split("\\|", -1);
            if (values.length != 2) {
                throw new MeetingException(MeetingErrorCode.INVALID_CURSOR);
            }
            return new MeetingCursor(LocalDateTime.parse(values[0]), Long.valueOf(values[1]));
        } catch (MeetingException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new MeetingException(MeetingErrorCode.INVALID_CURSOR);
        } catch (RuntimeException e) {
            throw new MeetingException(MeetingErrorCode.INVALID_CURSOR);
        }
    }

    public String encode() {
        String payload = effectiveStartAt + DELIMITER + meetingId;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }
}
