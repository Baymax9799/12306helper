package com.jactil.tickethelper.engine;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * 全局限速器：所有任务的查询与提交共享一个节奏，避免多任务叠加把账号打爆。
 * 用时间戳而不是信号量，间隔可以随时调小/调大。
 */
public final class RateLimiter {

    private final AtomicLong nextAllowedNanos = new AtomicLong(0);
    private volatile long intervalMs;
    private volatile boolean paused;

    public RateLimiter(long intervalMs) {
        this.intervalMs = Math.max(1, intervalMs);
    }

    public long intervalMs() {
        return intervalMs;
    }

    public void setIntervalMs(long intervalMs) {
        this.intervalMs = Math.max(1, intervalMs);
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    /** 阻塞到可以发下一个请求。返回 false 表示等待期间被中断。 */
    public boolean acquire() {
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                return false;
            }
            long now = System.nanoTime();
            long next = nextAllowedNanos.get();
            if (now >= next && nextAllowedNanos.compareAndSet(next, now + intervalMs * 1_000_000L)) {
                return !paused;
            }
            long waitNanos = Math.max(0, next - now);
            LockSupport.parkNanos(Math.min(waitNanos, TimeUnit.MILLISECONDS.toNanos(50)));
        }
    }
}
