package com.backend.meety.global.migration;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;






@Component
public class MigrationWriteGate {
    private final MigrationGateProperties properties;


    private final ReentrantLock lock = new ReentrantLock(true);

    private final Condition opened = lock.newCondition();


    private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);

    private boolean closed;

    private int active;

    private int pending;
    private long timedOut;
    private long rejected;

    public MigrationWriteGate(MigrationGateProperties properties) {
        this.properties = properties;
        closed = properties.enabled() && properties.initiallyClosed();
    }


    public Permit enter() {
        return acquire(true);
    }


    public Permit tryEnter() {
        return acquire(false);
    }

    private Permit acquire(boolean wait) {
        if (!properties.enabled()) {

            return () -> {};
        }


        if (depth.get() > 0) {
            depth.set(depth.get() + 1);
            return this::release;
        }
        lock.lock();
        try {

            if (closed && !wait) {
                return null;
            }
            if (closed) {

                if (pending >= properties.maxPending()) {
                    rejected++;
                    throw unavailable("DB 이전 대기 요청 한도를 초과했습니다.");
                }
                pending++;
                try {
                    long remaining = properties.maxWait().toNanos();


                    while (closed) {
                        if (remaining <= 0) {
                            timedOut++;
                            throw unavailable("DB 이전 대기 시간이 초과됐습니다. 작업을 실행하지 않았습니다.");
                        }
                        remaining = opened.awaitNanos(remaining);
                    }

                    if (remaining <= 0) {
                        timedOut++;
                        throw unavailable("DB 이전 대기 시간이 초과됐습니다. 작업을 실행하지 않았습니다.");
                    }
                } catch (InterruptedException e) {

                    Thread.currentThread().interrupt();
                    throw unavailable("DB 이전 대기가 중단됐습니다.");
                } finally {

                    pending--;
                }
            }

            active++;
            depth.set(1);
            return this::release;
        } finally {
            lock.unlock();
        }
    }


    private void release() {
        int nesting = depth.get();
        if (nesting > 1) {
            depth.set(nesting - 1);
            return;
        }

        depth.remove();
        lock.lock();
        try {
            active--;
        } finally {
            lock.unlock();
        }
    }


    public boolean ownsPermit() {
        return depth.get() > 0;
    }


    public Status close() {
        lock.lock();
        try {
            closed = true;
            return snapshot();
        } finally {
            lock.unlock();
        }
    }


    public Status open() {
        lock.lock();
        try {
            closed = false;

            opened.signalAll();
            return snapshot();
        } finally {
            lock.unlock();
        }
    }


    public Status status() {
        lock.lock();
        try {
            return snapshot();
        } finally {
            lock.unlock();
        }
    }

    private Status snapshot() {

        return new Status(closed, closed && active == 0, active, pending, timedOut, rejected);
    }

    private MigrationGateUnavailableException unavailable(String message) {
        return new MigrationGateUnavailableException(message);
    }

    public record Status(boolean closed, boolean drained, int active, int pending,
                         long timedOut, long rejected) {}


    public interface Permit extends AutoCloseable {
        @Override
        void close();
    }
}
