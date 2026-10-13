package com.backend.meety.domain.recording.owner;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RecordingCommandChannels {

    public static String of(String serverId) {
        return "recording-command:" + serverId;
    }

    public static String ofOwner(String owner) {
        return of(owner.substring(0, owner.lastIndexOf(':')));
    }
}
