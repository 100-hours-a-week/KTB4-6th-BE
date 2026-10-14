package com.backend.meety.domain.recording.owner;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ServerInstance {

    public static final String OWNER_DELIMITER = ":";

    private final String serverId;
    private final AtomicLong connectionCounter = new AtomicLong();

    public ServerInstance() {
        this(UUID.randomUUID().toString());
    }

    ServerInstance(String serverId) {
        this.serverId = serverId;
        log.info("서버 ID를 생성했습니다. serverId={}, hostname={}", serverId, hostname());
    }

    public String serverId() {
        return serverId;
    }

    public String nextOwner() {
        return serverId + OWNER_DELIMITER + connectionCounter.incrementAndGet();
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
