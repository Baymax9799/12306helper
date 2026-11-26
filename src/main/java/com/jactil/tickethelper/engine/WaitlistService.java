package com.jactil.tickethelper.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jactil.tickethelper.core.client.TicketClient;
import com.jactil.tickethelper.core.passenger.PassengerSync;
import com.jactil.tickethelper.core.query.TrainRow;
import com.jactil.tickethelper.core.store.Store;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 候补下单。
 *
 * <p><b>状态：实验性，默认关闭</b>。12306 的 confirmHB 参数是几段拼装字符串
 * （passengerInfo / journey / train / seatType），格式随改版会变，且猜错参数
 * 可能触发风控。所以默认不启用，只在显式设置环境变量
 * {@code WAITLIST_ENABLED=true} 时才真正提交；未启用时任务会退化为普通抢票。
 *
 * <p>启用后如果返回语义发生变化，会以 UNAVAILABLE 结束并记录服务端原话，方便实盘校正。
 */
public final class WaitlistService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PATH = "/otn/confirmPassenger/confirmHB";

    public enum Result {
        /** 候补提交成功 */
        CREATED,
        /** 当前无法候补，应退化为抢票 */
        UNAVAILABLE,
        /** 未启用（默认） */
        DISABLED,
        /** 被风控或会话失效 */
        RISK,
        /** 其它错误 */
        ERROR
    }

    public record Outcome(Result result, String orderNo, String message) {
    }

    private final TicketClient client;
    private final String base;
    private final boolean enabled;

    public WaitlistService(TicketClient client) {
        this(client, "https://kyfw.12306.cn", Boolean.parseBoolean(System.getenv("WAITLIST_ENABLED")));
    }

    public WaitlistService(TicketClient client, String base, boolean enabled) {
        this.client = client;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public Outcome submit(Store.TaskRow task, TrainRow train, String seatName,
                          List<PassengerSync.Remote> passengers) {
        if (!enabled) {
            return new Outcome(Result.DISABLED, null,
                    "候补下单未启用（WAITLIST_ENABLED=false），已退化为普通抢票");
        }
        if (passengers.isEmpty()) {
            return new Outcome(Result.ERROR, null, "没有可用乘车人");
        }
        try {
            Map<String, String> form = buildForm(task, train, seatName, passengers);
            String body = client.post(base + PATH, form);
            JsonNode root = JSON.readTree(body);
            if (!root.path("status").asBoolean(false)) {
                return new Outcome(Result.UNAVAILABLE, null, shorten(body));
            }
            JsonNode data = root.path("data");
            if (data.path("flag").asBoolean(false)) {
                String orderNo = data.path("orderId").asText(data.path("waitlistOrderNo").asText(""));
                return new Outcome(Result.CREATED, orderNo, "候补已提交");
            }
            return new Outcome(Result.UNAVAILABLE, null, data.toString());
        } catch (TicketClient.RedirectedException e) {
            return new Outcome(Result.RISK, null, "被风控或会话失效");
        } catch (IOException e) {
            return new Outcome(Result.ERROR, null, "候补请求失败：" + e.getMessage());
        }
    }

    /** 参数拼装单独抽出来，方便单测断言；真实语义待实盘确认。 */
    static Map<String, String> buildForm(Store.TaskRow task, TrainRow train, String seatName,
                                         List<PassengerSync.Remote> passengers) {
        StringBuilder passengerInfo = new StringBuilder();
        for (PassengerSync.Remote p : passengers) {
            if (passengerInfo.length() > 0) {
                passengerInfo.append('_');
            }
            passengerInfo.append(p.name()).append(',').append(p.idTypeCode()).append(',')
                    .append(p.idNo()).append(',').append(p.ticketType()).append(',')
                    .append("1").append(',').append('N');
        }
        String date = task.dates().isEmpty() ? "" : task.dates().get(0);
        return Map.of(
                "passengerInfo", passengerInfo.toString(),
                "journey", train.fromCode() + "," + train.toCode() + "," + date,
                "train", train.trainNo() + "," + train.startTime() + "," + train.arriveTime()
                        + "," + train.duration(),
                "seatType", seatName,
                "_json_att", "");
    }

    private static String shorten(String body) {
        return body == null ? "" : (body.length() <= 120 ? body : body.substring(0, 120) + "…");
    }
}
