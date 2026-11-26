package com.jactil.tickethelper.engine;

import com.jactil.tickethelper.core.store.Store;

import java.time.Duration;
import java.time.Instant;

/** 开抢时刻的相位计算：PENDING → PREHEAT → BURST → STEADY。 */
public final class Timing {

    public enum Phase {
        /** 还没到预热时刻 */
        PENDING,
        /** 开抢前 30 秒 */
        PREHEAT,
        /** 开抢后 60 秒，提频 */
        BURST,
        /** 之后回落到任务自己的间隔 */
        STEADY
    }

    public static final Duration PREHEAT_WINDOW = Duration.ofSeconds(30);
    public static final Duration BURST_WINDOW = Duration.ofSeconds(60);
    public static final long BURST_INTERVAL_MS = 300;
    public static final long PREHEAT_INTERVAL_MS = 1000;
    public static final long MIN_INTERVAL_MS = 500;
    public static final long MAX_INTERVAL_MS = 5000;

    private Timing() {
    }

    public static Phase phase(Store.TaskRow task, Instant now) {
        if (task.startAt() == null) {
            return Phase.STEADY;
        }
        if (now.isBefore(task.startAt().minus(PREHEAT_WINDOW))) {
            return Phase.PENDING;
        }
        if (now.isBefore(task.startAt())) {
            return Phase.PREHEAT;
        }
        if (now.isBefore(task.startAt().plus(BURST_WINDOW))) {
            return Phase.BURST;
        }
        return Phase.STEADY;
    }

    /** 该相位下的轮询间隔。 */
    public static long intervalMs(Store.TaskRow task, Phase phase) {
        return switch (phase) {
            case BURST -> BURST_INTERVAL_MS;
            case PREHEAT -> PREHEAT_INTERVAL_MS;
            case PENDING, STEADY -> clamp(task.intervalMs());
        };
    }

    public static long clamp(long intervalMs) {
        return Math.max(MIN_INTERVAL_MS, Math.min(MAX_INTERVAL_MS, intervalMs));
    }
}
