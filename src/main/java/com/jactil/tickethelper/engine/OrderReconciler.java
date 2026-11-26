package com.jactil.tickethelper.engine;

import com.jactil.tickethelper.core.store.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 与 12306 对账。
 *
 * <p>要解决的场景：进程在 confirmSingleForQueue 之后、写库之前被杀，
 * 12306 侧留了一张未支付订单，本地却毫无记录 —— 这种情况下账号额度已经被吃掉，
 * 服务必须能发现并立刻停手。
 */
public final class OrderReconciler {

    private static final Logger log = LoggerFactory.getLogger(OrderReconciler.class);

    /** 拉取 12306 侧未完成订单号。抽成接口方便测试与实盘替换。 */
    public interface Fetcher {
        List<String> unfinishedOrderNos() throws Exception;
    }

    private final Store store;
    private final AccountGate gate;
    private final EventBus bus;
    private final Fetcher fetcher;

    public OrderReconciler(Store store, AccountGate gate, EventBus bus, Fetcher fetcher) {
        this.store = store;
        this.gate = gate;
        this.bus = bus;
        this.fetcher = fetcher;
    }

    /** 返回发现的孤儿订单数量。异常不外抛，只记 WARN（对账失败不能拖垮主流程）。 */
    public int runOnce() {
        List<String> remote;
        try {
            remote = fetcher.unfinishedOrderNos();
        } catch (Exception e) {
            log.warn("对账失败（接口可能改版或未登录）：{}", e.getMessage());
            return 0;
        }
        int orphans = 0;
        for (String orderNo : remote) {
            if (orderNo == null || orderNo.isBlank() || store.orderExists(orderNo)) {
                continue;
            }
            store.insertOrder(null, orderNo, null, null, null);
            orphans++;
            log.warn("发现本地没有记录的未支付订单：{}", orderNo);
        }
        if (orphans > 0) {
            gate.onOrderCreated(remote.get(0));
            bus.warn(null, "对账发现 " + orphans + " 张未支付订单，已暂停所有任务，请去 12306 处理");
        }
        return orphans;
    }
}
