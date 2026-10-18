package com.backend.meety.domain.meeting.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class MeetingSseBlockingTest {

    @Test
    @DisplayName("순차 fan-out은 느린 emitter가 반환할 때까지 다음 참가자와 broadcast 반환을 기다린다")
    void slowEmitterBlocksLaterRecipientsAndBroadcastReturn() throws Exception {
        // 테스트 목적:
        // 특정 참가자의 전송이 막히면 같은 broadcast의 나머지 참가자와
        // broadcast 호출자도 전송 완료까지 대기하는지 검증한다.

        // given
        MeetingSseRegistry registry = new MeetingSseRegistry(new SimpleMeterRegistry());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger sends = new AtomicInteger();
        for (long user = 1; user <= 3; user++) {
            registry.register(100L, user, new SseEmitter() {
                @Override
                public void send(SseEventBuilder builder) throws IOException {
                    if (sends.incrementAndGet() == 1) {
                        entered.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw new IOException("slow emitter was not released");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException(e);
                        }
                    }
                }
            });
        }
        var worker = Executors.newSingleThreadExecutor();

        // when
        long started = System.nanoTime();
        var broadcast = worker.submit(() -> registry.broadcast(100L, "TRANSCRIPT_CREATED", "segment"));
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            // then
            assertThat(broadcast.isDone()).isFalse();
            assertThat(sends).hasValue(1);
            assertThatThrownBy(() -> broadcast.get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            assertThat(broadcast.get(2, TimeUnit.SECONDS)).isEqualTo(3);
            assertThat(sends).hasValue(3);
            System.out.printf("SSE_RECIPIENT_BLOCKING broadcast_ms=%.3f recipients=3%n",
                    (System.nanoTime() - started) / 1_000_000.0);
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }
}
