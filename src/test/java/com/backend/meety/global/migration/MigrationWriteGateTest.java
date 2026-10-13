package com.backend.meety.global.migration;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MigrationWriteGateTest {
    private MigrationWriteGate gate(boolean closed, Duration wait, int pending) {
        return new MigrationWriteGate(new MigrationGateProperties(true, closed, wait, pending, "a".repeat(32)));
    }

    @Test
    void closedRequestExecutesOnlyAfterOpen() throws Exception {
        var gate = gate(true, Duration.ofSeconds(3), 2);
        var writes = new AtomicInteger();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> {
                try (var permit = gate.enter()) {
                    writes.incrementAndGet();
                }
            });
            awaitPending(gate);
            assertEquals(0, writes.get());
            gate.open();
            result.get(2, TimeUnit.SECONDS);
            assertEquals(1, writes.get());
            assertEquals(0, gate.status().active());
        }
    }

    @Test
    void timeoutNeverExecutesAfterLaterOpen() throws Exception {
        var gate = gate(true, Duration.ofMillis(50), 2);
        var writes = new AtomicInteger();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> {
                try (var permit = gate.enter()) {
                    writes.incrementAndGet();
                }
            });
            var error = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertInstanceOf(MigrationGateUnavailableException.class, error.getCause());
            gate.open();
            assertEquals(0, writes.get());
            assertEquals(1, gate.status().timedOut());
            assertEquals(0, gate.status().pending());
        }
    }

    @Test
    void pendingCapacityRejectsAdditionalRequest() throws Exception {
        var gate = gate(true, Duration.ofSeconds(2), 1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> {
                try (var permit = gate.enter()) { }
            });
            try {
                awaitPending(gate);
                assertThrows(MigrationGateUnavailableException.class, gate::enter);
                assertEquals(1, gate.status().rejected());
            } finally {
                gate.open();
            }
            first.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void closeAllowsAdmittedNestedWorkToFinishBeforeDrained() {
        var gate = gate(false, Duration.ofSeconds(1), 1);
        try (var outer = gate.enter()) {
            assertFalse(gate.close().drained());
            try (var inner = gate.enter()) {
                assertEquals(1, gate.status().active());
            }
            assertFalse(gate.status().drained());
        }
        assertTrue(gate.status().drained());
        assertNull(gate.tryEnter());
    }

    @Test
    void interruptedWaitRemovesPendingRequest() throws Exception {
        var gate = gate(true, Duration.ofSeconds(3), 1);
        var interrupted = new AtomicInteger();
        var thread = new Thread(() -> {
            try {
                gate.enter();
                fail("중단된 요청에 허가를 주면 안 된다");
            } catch (MigrationGateUnavailableException e) {
                if (Thread.currentThread().isInterrupted()) interrupted.incrementAndGet();
            }
        });
        thread.start();
        awaitPending(gate);
        thread.interrupt();
        thread.join(2000);
        assertFalse(thread.isAlive());
        assertEquals(1, interrupted.get());
        assertEquals(0, gate.status().pending());
    }

    @Test
    void disabledGateDoesNotBlockAndEnabledGateRequiresSecret() {
        var gate = new MigrationWriteGate(new MigrationGateProperties(false, true,
                Duration.ofSeconds(1), 1, ""));
        try (var permit = gate.enter()) {
            assertFalse(gate.status().closed());
        }
        assertThrows(IllegalArgumentException.class, () -> new MigrationGateProperties(true, true,
                Duration.ofSeconds(1), 1, ""));
    }

    private void awaitPending(MigrationWriteGate gate) {

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (gate.status().pending() == 0 && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(1, gate.status().pending());
    }
}
