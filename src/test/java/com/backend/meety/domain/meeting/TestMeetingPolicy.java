package com.backend.meety.domain.meeting;

public final class TestMeetingPolicy {

    public static final long DAILY_CREATE_LIMIT = 5L;

    public static final MeetingPolicy DEFAULT = new MeetingPolicy(DAILY_CREATE_LIMIT);

    private TestMeetingPolicy() {
    }
}
