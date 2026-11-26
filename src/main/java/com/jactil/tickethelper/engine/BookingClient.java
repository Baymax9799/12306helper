package com.jactil.tickethelper.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jactil.tickethelper.core.client.TicketClient;
import com.jactil.tickethelper.core.passenger.PassengerSync;
import com.jactil.tickethelper.core.query.TrainRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 12306 下单的六个步骤。参数名照官方前端来，顺序不能换：
 * submitOrderRequest → initDc → checkOrderInfo → getQueueCount →
 * confirmSingleForQueue → queryOrderWaitTime。
 *
 * <p><b>风险</b>：接口参数随 12306 改版会变，本类不猜测、不重试错参数，
 * 失败就把服务端原话带回去，方便实盘校正。
 */
public final class BookingClient {

    private static final Logger log = LoggerFactory.getLogger(BookingClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Pattern TOKEN = Pattern.compile("globalRepeatSubmitToken\\s*=\\s*'([^']+)'");
    private static final Pattern LEFT_TICKET = Pattern.compile("['\"]leftTicketStr['\"]\\s*:\\s*['\"]([^'\"]*)['\"]");
    private static final Pattern KEY_CHECK = Pattern.compile("['\"]key_check_isChange['\"]\\s*:\\s*['\"]([^'\"]*)['\"]");
    private static final Pattern TRAIN_LOCATION = Pattern.compile("['\"]train_location['\"]\\s*:\\s*['\"]([^'\"]*)['\"]");

    public record Tokens(String repeatToken, String leftTicketStr, String keyCheckIsChange, String trainLocation) {
    }

    public record StepResult(boolean ok, String message) {
    }

    public record BookingOutcome(String orderNo, String message) {
        public boolean success() {
            return orderNo != null && !orderNo.isBlank();
        }
    }

    private final TicketClient client;
    private final String base;

    public BookingClient(TicketClient client) {
        this(client, "https://kyfw.12306.cn");
    }

    public BookingClient(TicketClient client, String base) {
        this.client = client;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** 第 1 步。返回 null 表示通过，否则是服务端给的拒绝原因。 */
    public String submitOrderRequest(TrainRow train, String trainDate) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("secretStr", URLDecoder.decode(train.secretStr(), StandardCharsets.UTF_8));
        form.put("train_date", trainDate);
        form.put("back_train_date", java.time.LocalDate.now().toString());
        form.put("tour_flag", "dc");
        form.put("purpose_codes", "ADULT");
        form.put("from_station_name", train.fromName());
        form.put("to_station_name", train.toName());
        form.put("undefined", "undefined");
        String body = client.post(base + "/otn/leftTicket/submitOrderRequest", form);
        JsonNode root = JSON.readTree(body);
        boolean status = root.path("status").asBoolean(false);
        String messages = root.path("messages").toString();
        if (status) {
            return null;
        }
        return messages.contains("null") || messages.isBlank() ? body : messages;
    }

    /** 第 2 步。从 initDc 页面里抠出后续步骤要用的 token 与票据串。 */
    public Tokens initDc() throws IOException {
        String html = client.post(base + "/otn/confirmPassenger/initDc", Map.of("_json_att", ""));
        String token = firstGroup(TOKEN, html);
        if (token == null) {
            throw new IOException("initDc 页面里没有 globalRepeatSubmitToken，"
                    + "通常是登录态失效或 12306 改版了");
        }
        return new Tokens(token,
                nullToEmpty(firstGroup(LEFT_TICKET, html)),
                nullToEmpty(firstGroup(KEY_CHECK, html)),
                nullToEmpty(firstGroup(TRAIN_LOCATION, html)));
    }

    /** 第 3 步：校验乘客与席别。 */
    public StepResult checkOrderInfo(Tokens tokens, String passengerTicketStr,
                                     String oldPassengerStr) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("cancel_flag", "2");
        form.put("bed_level_order_num", "000000000000000000000000000000");
        form.put("passengerTicketStr", passengerTicketStr);
        form.put("oldPassengerStr", oldPassengerStr);
        form.put("tour_flag", "dc");
        form.put("randCode", "");
        form.put("whatsSelect", "1");
        form.put("_json_att", "");
        form.put("REPEAT_SUBMIT_TOKEN", tokens.repeatToken());
        String body = client.post(base + "/otn/confirmPassenger/checkOrderInfo", form);
        JsonNode data = JSON.readTree(body).path("data");
        boolean submitStatus = data.path("submitStatus").asBoolean(false);
        String errMsg = data.path("errMsg").asText("");
        return new StepResult(submitStatus, submitStatus ? "通过" : (errMsg.isEmpty() ? body : errMsg));
    }

    /** 第 4 步：排队人数 vs 余票。返回 null 表示可以继续，否则是拒绝原因。 */
    public String getQueueCount(Tokens tokens, TrainRow train, String trainDate,
                                String seatCode, String leftTicketStr) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("train_date", trainDate);
        form.put("train_no", train.trainNo());
        form.put("stationTrainCode", train.trainNo());
        form.put("seatType", seatCode);
        form.put("fromStationTelecode", train.fromCode());
        form.put("toStationTelecode", train.toCode());
        form.put("leftTicket", leftTicketStr);
        form.put("purpose_codes", "ADULT");
        form.put("train_location", tokens.trainLocation());
        form.put("_json_att", "");
        form.put("REPEAT_SUBMIT_TOKEN", tokens.repeatToken());
        String body = client.post(base + "/otn/confirmPassenger/getQueueCount", form);
        JsonNode root = JSON.readTree(body);
        if (!root.path("status").asBoolean(false)) {
            return body;
        }
        JsonNode data = root.path("data");
        int queue = data.path("count").asInt(0);
        int ticket = data.path("ticket").asInt(0);
        if (ticket > 0 && queue > ticket) {
            return "排队人数 " + queue + " 超过余票 " + ticket;
        }
        return null;
    }

    /** 第 5 步：真正提交。 */
    public StepResult confirmSingleForQueue(Tokens tokens, String passengerTicketStr,
                                            String oldPassengerStr) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("passengerTicketStr", passengerTicketStr);
        form.put("oldPassengerStr", oldPassengerStr);
        form.put("randCode", "");
        form.put("purpose_codes", "00");
        form.put("key_check_isChange", tokens.keyCheckIsChange());
        form.put("leftTicketStr", tokens.leftTicketStr());
        form.put("train_location", tokens.trainLocation());
        form.put("choose_seats", "");
        form.put("seatDetailType", "000");
        form.put("whatsSelect", "1");
        form.put("roomType", "00");
        form.put("dwAll", "N");
        form.put("_json_att", "");
        form.put("REPEAT_SUBMIT_TOKEN", tokens.repeatToken());
        String body = client.post(base + "/otn/confirmPassenger/confirmSingleForQueue", form);
        JsonNode data = JSON.readTree(body).path("data");
        boolean ok = data.path("submitStatus").asBoolean(false);
        return new StepResult(ok, ok ? "已提交" : body);
    }

    /** 第 6 步：轮询排队结果，最多 60 秒。 */
    public BookingOutcome awaitOrder(Tokens tokens, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String lastMessage = "";
        while (System.currentTimeMillis() < deadline) {
            String url = base + "/otn/confirmPassenger/queryOrderWaitTime?random="
                    + System.currentTimeMillis() + "&tourFlag=dc&_json_att=&REPEAT_SUBMIT_TOKEN="
                    + tokens.repeatToken();
            String body = client.get(url);
            JsonNode root = JSON.readTree(body);
            JsonNode data = root.path("data");
            String orderId = data.path("orderId").asText("");
            if (!orderId.isEmpty() && !"null".equals(orderId)) {
                return new BookingOutcome(orderId, "下单成功");
            }
            lastMessage = data.path("msg").asText("");
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return new BookingOutcome(null, lastMessage.isEmpty() ? "排队超时，未拿到订单号" : lastMessage);
    }

    /**
     * 拼 passengerTicketStr / oldPassengerStr。
     * 格式：{席别},{票种},{姓名},{证件类型},{证件号},{手机号},N，多人用 _ 连接。
     */
    public static String[] buildPassengerStrings(String seatCode, List<PassengerSync.Remote> passengers) {
        List<String> tickets = new ArrayList<>();
        List<String> old = new ArrayList<>();
        for (PassengerSync.Remote p : passengers) {
            String type = "1";
            tickets.add(String.join(",", seatCode, type, p.name(), p.idTypeCode(), p.idNo(), "N"));
            old.add(p.name() + "," + p.idTypeCode() + "," + p.idNo() + ",1_");
        }
        return new String[]{String.join("_", tickets), String.join("", old)};
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
