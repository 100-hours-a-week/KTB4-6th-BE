package com.backend.meety.global.config;

import org.springframework.core.task.TaskDecorator;

public final class ReadMasterContext {

    private static final ThreadLocal<Boolean> FORCED = new ThreadLocal<>();

    private ReadMasterContext() {
    }

    public static boolean isForced() {
        return Boolean.TRUE.equals(FORCED.get());
    }

    public static void force() {
        FORCED.set(Boolean.TRUE);
    }

    public static void clear() {
        FORCED.remove();
    }

    public static TaskDecorator taskDecorator() {
        return task -> () -> {
            force();
            try {
                task.run();
            } finally {
                clear();
            }
        };
    }
}
