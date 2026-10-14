package com.backend.meety.domain.meeting;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MeetingAttendeePolicy {

    public static final int MIN_ATTENDEES = 1;
    public static final int MAX_ATTENDEES = 10;
    public static final int MAX_CUSTOM_ATTENDEES = 2;
    public static final int MAX_CUSTOM_NAME_LENGTH = 10;
}
