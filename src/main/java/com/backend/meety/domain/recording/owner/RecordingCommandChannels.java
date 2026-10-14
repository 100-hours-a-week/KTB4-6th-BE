package com.backend.meety.domain.recording.owner;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RecordingCommandChannels {

    public static final String PREFIX = "recording-command:";

    public static String of(String serverId) {
        return PREFIX + serverId;
    }

    public static String ofOwner(String owner) {
        return of(owner.substring(0, owner.lastIndexOf(ServerInstance.OWNER_DELIMITER)));
    }
}
