package com.jactil.tickethelper.engine;

import com.jactil.tickethelper.core.config.Settings;
import com.jactil.tickethelper.core.store.Store;
import com.jactil.tickethelper.core.time.ServerClock;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.function.Supplier;

/**
 * 账号闸门。12306 的规则是「自然日内未支付或取消订单合计超 3 次，账号当天被锁」，
 * 所以抢到一单之后必须整体停手，而不是只停当前任务。
 */
public final class AccountGate {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final Store store;
    private final ServerClock clock;
    private final Supplier<Settings> settings;

    public AccountGate(Store store, ServerClock clock, Supplier<Settings> settings) {
        this.store = store;
        this.clock = clock;
        this.settings = settings;
    }

    public LocalDate today() {
        return LocalDate.ofInstant(clock.now(), ZONE);
    }

    public boolean held() {
        return "HOLD".equals(store.gate().state());
    }

    /** 能不能发起一次提交。HOLD 或当日额度用完都不行。 */
    public boolean canSubmit() {
        if (held()) {
            return false;
        }
        return store.dailyStat(today()).created() < Math.max(1, settings.get().dailyOrderLimit);
    }

    /** 抢到单：立即 HOLD，并记一次当日下单。 */
    public void onOrderCreated(String orderNo) {
        store.bumpDailyStat(today(), "orders_created", 1);
        store.setGate("HOLD", "已抢到订单 " + orderNo + "，请去 12306 付款或取消后回来解锁");
    }

    /** 人工确认已处理：PAID 计入已付，CANCELLED 计入已取消。 */
    public void unlock(String action) {
        String normalized = action == null ? "" : action.trim().toUpperCase();
        LocalDate day = today();
        if ("PAID".equals(normalized)) {
            store.bumpDailyStat(day, "orders_paid", 1);
        } else if ("CANCELLED".equals(normalized)) {
            store.bumpDailyStat(day, "orders_cancelled", 1);
        }
        store.setGate("OPEN", null);
    }

    public record Status(String state, String reason, int created, int paid, int cancelled, int limit, String day) {
    }

    public Status status() {
        Store.GateRow gate = store.gate();
        Store.DailyStat stat = store.dailyStat(today());
        return new Status(gate.state(), gate.reason(), stat.created(), stat.paid(), stat.cancelled(),
                Math.max(1, settings.get().dailyOrderLimit), today().toString());
    }
}
