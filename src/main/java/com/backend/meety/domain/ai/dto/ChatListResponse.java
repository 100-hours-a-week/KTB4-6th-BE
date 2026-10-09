package com.backend.meety.domain.ai.dto;

import com.backend.meety.domain.ai.entity.AiChatbotMessage;
import java.util.List;

public record ChatListResponse(
        List<ChatMessageResponse> messages,
        Long nextCursor,
        boolean hasNext
) {

    public static ChatListResponse of(List<AiChatbotMessage> fetched, int pageSize) {
        boolean hasNext = fetched.size() > pageSize;
        List<AiChatbotMessage> page = hasNext ? fetched.subList(0, pageSize) : fetched;
        Long nextCursor = hasNext ? page.getLast().getId() : null;
        return new ChatListResponse(page.stream().map(ChatMessageResponse::from).toList(), nextCursor, hasNext);
    }
}
