package com.backend.meety.domain.ai.client;

import java.time.OffsetDateTime;

public record SttPrewarmRequest(Long meetingId, OffsetDateTime startsAt) {
}
