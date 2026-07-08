package com.eoframework.common;

import com.eoframework.EOFramework;

public final class EOFPerf {
    private static final long WARN_NANOS = 20_000_000L;

    private EOFPerf() {}

    public static void time(String name, Runnable runnable) {
        long start = System.nanoTime();
        try {
            runnable.run();
        } finally {
            warnIfSlow(name, System.nanoTime() - start);
        }
    }

    public static long start() {
        return System.nanoTime();
    }

    public static void warnIfSlow(String name, long elapsedNanos) {
        if (elapsedNanos > WARN_NANOS) {
            EOFramework.LOGGER.warn("[EOF Perf] {} took {} ms", name, elapsedNanos / 1_000_000.0D);
        }
    }
}
