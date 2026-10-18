package com.backend.meety.domain.meeting.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

class MeetingSseSubscriptionConfigTest {

    private final MeetingSseSubscriptionConfig config = new MeetingSseSubscriptionConfig();

    @Test
    @DisplayName("빈 생성이 끝나면 meeting-sse 채널에 회의 SSE 구독자를 등록한다")
    void registersSubscriberToMeetingSseChannel() {
        RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
        MeetingSseSubscriber subscriber = mock(MeetingSseSubscriber.class);
        ChannelTopic topic = config.meetingSseTopic();

        config.meetingSseSubscription(container, subscriber, topic).afterSingletonsInstantiated();

        assertThat(topic.getTopic()).isEqualTo("meeting-sse");
        verify(container).addMessageListener(subscriber, topic);
    }
}
