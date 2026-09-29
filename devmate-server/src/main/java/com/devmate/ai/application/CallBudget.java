package com.devmate.ai.application;

import com.devmate.common.api.ErrorCode;
import java.time.Duration;

/** A monotonic, cumulative budget propagated through nested network boundaries on one thread. */
public final class CallBudget implements AutoCloseable {
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();
    private final Long previous;

    private CallBudget(Duration duration) {
        previous = DEADLINE.get();
        long deadline = System.nanoTime() + duration.toNanos();
        DEADLINE.set(previous == null ? deadline : Math.min(previous, deadline));
    }

    public static CallBudget open(Duration duration) { return new CallBudget(duration); }

    public static Duration cap(Duration maximum) {
        Long deadline = DEADLINE.get();
        if (deadline == null) return maximum;
        long remaining = deadline - System.nanoTime();
        if (remaining < 1_000_000) throw new AiGatewayException(ErrorCode.AI_PROVIDER_TIMEOUT);
        return Duration.ofNanos(Math.min(remaining, maximum.toNanos()));
    }

    @Override public void close() {
        if (previous == null) DEADLINE.remove(); else DEADLINE.set(previous);
    }
}
