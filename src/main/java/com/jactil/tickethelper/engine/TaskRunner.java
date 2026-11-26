package com.jactil.tickethelper.engine;

import com.jactil.tickethelper.core.client.TicketClient;
import com.jactil.tickethelper.core.query.LeftTicketQuery;
import com.jactil.tickethelper.core.query.SeatInfo;
import com.jactil.tickethelper.core.query.TrainRow;
import com.jactil.tickethelper.core.store.Store;
import com.jactil.tickethelper.core.time.ServerClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * 一个任务一个线程：按相位节奏查询余票，命中后交给 {@link BookingAttempt}。
 */
public final class TaskRunner implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(TaskRunner.class);

    public record Match(TrainRow train, String seatName) {
    }

    public record Deps(Store store,
                       LeftTicketQuery query,
                       RateLimiter limiter,
                       Blacklist blacklist,
                       EventBus bus,
                       ServerClock clock,
                       AccountGate gate,
                       java.util.function.Supplier<BookingAttempt> booking,
                       Function<String, String> stationName) {
    }

    private final Store.TaskRow task;
    private final Deps deps;
    private volatile boolean running = true;
    private Thread thread;

    public TaskRunner(Store.TaskRow task, Deps deps) {
        this.task = task;
        this.deps = deps;
    }

    public void start() {
        thread = new Thread(this, "task-" + task.id());
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public void run() {
        deps.bus().info(task.id(), "任务启动：" + task.name() + "（"
                + task.from() + "→" + task.to() + "）");
        try {
            while (running) {
                Store.TaskRow current = deps.store().findTask(task.id()).orElse(null);
                if (current == null || !"RUNNING".equals(current.status())) {
                    return;
                }
                Timing.Phase phase = Timing.phase(current, deps.clock().now());
                if (phase == Timing.Phase.PENDING) {
                    deps.bus().info(task.id(), "未到开抢时刻，等待中");
                    if (!sleep(5_000)) {
                        return;
                    }
                    continue;
                }
                if (!deps.gate().canSubmit()) {
                    deps.store().updateTaskStatus(task.id(), "HELD");
                    deps.bus().warn(task.id(), "账号闸门已关闭（已抢到单或今日额度用完），任务挂起");
                    return;
                }
                if (!sweep(current)) {
                    return;
                }
                long interval = Timing.intervalMs(current, phase);
                if (!sleep(interval)) {
                    return;
                }
            }
        } catch (RuntimeException e) {
            log.warn("任务 {} 异常退出", task.id(), e);
            deps.bus().error(task.id(), "任务异常：" + e.getMessage());
            deps.store().updateTaskStatus(task.id(), "FAILED");
        } finally {
            running = false;
        }
    }

    /** 扫一遍所有日期，命中就处理。返回 false 表示任务该结束了。 */
    private boolean sweep(Store.TaskRow current) {
        int paxCount = Math.max(1, current.passengerIds().size());
        for (String dateText : current.dates()) {
            LocalDate date;
            try {
                date = LocalDate.parse(dateText);
            } catch (RuntimeException e) {
                deps.bus().warn(task.id(), "日期格式不对，跳过：" + dateText);
                continue;
            }
            if (!deps.limiter().acquire()) {
                return false;
            }
            List<TrainRow> trains;
            try {
                trains = deps.query().query(date, current.from(), current.to(), "ADULT", deps.stationName());
            } catch (TicketClient.RedirectedException e) {
                deps.store().updateTaskStatus(task.id(), "NEED_VERIFY");
                deps.bus().warn(task.id(), "12306 要求重新验证，任务暂停，请重新扫码登录");
                return false;
            } catch (IOException e) {
                deps.bus().warn(task.id(), "查询失败：" + e.getMessage());
                continue;
            }
            Optional<Match> match = findMatch(current, trains, paxCount);
            if (match.isEmpty()) {
                continue;
            }
            if (!handleMatch(current, match.get())) {
                return false;
            }
        }
        return true;
    }

    /** 命中判定：先按车次过滤，再按任务里的席别顺序挑第一个够票的。 */
    public static Optional<Match> findMatch(Store.TaskRow task, List<TrainRow> trains, int paxCount) {
        List<String> wantedTrains = task.trains();
        List<String> wantedSeats = task.seatTypes();
        for (TrainRow train : trains) {
            if (!wantedTrains.isEmpty() && !wantedTrains.contains(train.trainNo())) {
                continue;
            }
            for (String seatName : wantedSeats) {
                SeatInfo seat = train.seat(seatName);
                if (seat != null && seat.enough(paxCount)) {
                    return Optional.of(new Match(train, seatName));
                }
            }
        }
        return Optional.empty();
    }

    private boolean handleMatch(Store.TaskRow current, Match match) {
        TrainRow train = match.train();
        String seat = match.seatName();
        if (deps.blacklist().blocked(train.trainNo(), seat, deps.clock().now())) {
            return true;
        }
        deps.bus().info(task.id(), "命中：" + train.trainNo() + " " + train.startTime()
                + " " + seat + "（" + train.seat(seat).raw() + "）");

        BookingAttempt.BookingResult result = deps.booking().get().attempt(current, train, seat);
        return switch (result.outcome()) {
            case SUCCESS -> {
                deps.store().insertOrder(task.id(), result.orderNo(), train.trainNo(), seat, null);
                deps.gate().onOrderCreated(result.orderNo());
                deps.store().updateTaskStatus(task.id(), "DONE");
                deps.bus().emit("ORDER_SUCCESS", task.id(), "INFO",
                        "抢票成功！订单号 " + result.orderNo() + "，请立即去 12306 付款");
                yield false;
            }
            case DISABLED -> {
                deps.store().updateTaskStatus(task.id(), "DONE");
                deps.bus().emit("MATCH", task.id(), "INFO",
                        "有票：" + train.trainNo() + " " + seat + "（当前为监控模式，未下单）");
                yield false;
            }
            case RISK -> {
                deps.store().updateTaskStatus(task.id(), "NEED_VERIFY");
                deps.bus().warn(task.id(), "下单被风控拦截，请重新扫码登录");
                yield false;
            }
            case NO_TICKET, QUEUE_FULL -> {
                int strikes = deps.blacklist().hit(train.trainNo(), seat, deps.clock().now());
                deps.bus().warn(task.id(), "提交未成功（" + result.message() + "），该车次进小黑屋，第 "
                        + strikes + " 次");
                yield true;
            }
            case ERROR -> {
                deps.bus().warn(task.id(), "下单出错：" + result.message());
                yield true;
            }
        };
    }

    /** 返回 false 表示被打断。 */
    private boolean sleep(long millis) {
        try {
            Thread.sleep(Math.max(50, millis));
            return running;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public List<String> debugState() {
        List<String> out = new ArrayList<>();
        out.add("task=" + task.id());
        out.add("running=" + running);
        out.add("phase=" + Timing.phase(task, deps.clock().now()));
        return out;
    }

    static Instant now(ServerClock clock) {
        return clock.now();
    }
}
