package com.jactil.tickethelper.engine;

import com.jactil.tickethelper.core.client.TicketClient;
import com.jactil.tickethelper.core.passenger.PassengerSync;
import com.jactil.tickethelper.core.query.SeatCatalog;
import com.jactil.tickethelper.core.query.SeatInfo;
import com.jactil.tickethelper.core.query.TrainRow;
import com.jactil.tickethelper.core.store.Store;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 真正下单。只做到「订单创建成功、等待支付」为止，绝不碰支付。
 */
public final class RealBooking implements BookingAttempt {

    private static final DateTimeFormatter RAW_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final long AWAIT_MS = 60_000;

    private final Store store;
    private final PassengerSync passengers;
    private final BookingClient client;

    public RealBooking(Store store, PassengerSync passengers, BookingClient client) {
        this.store = store;
        this.passengers = passengers;
        this.client = client;
    }

    @Override
    public BookingResult attempt(Store.TaskRow task, TrainRow train, String seatName) {
        String trainDate = toIsoDate(train.date());
        if (trainDate == null) {
            return BookingResult.of(Outcome.ERROR, "车次日期无法解析：" + train.date());
        }
        SeatInfo seat = train.seat(seatName);
        if (seat == null || !seat.available()) {
            return BookingResult.of(Outcome.NO_TICKET, "席别已无票：" + seatName);
        }
        List<PassengerSync.Remote> chosen;
        try {
            chosen = resolvePassengers(task, seat);
        } catch (IOException e) {
            return BookingResult.of(Outcome.ERROR, "拉取乘车人失败：" + e.getMessage());
        }
        if (chosen.isEmpty()) {
            return BookingResult.of(Outcome.ERROR, "没有可用的乘车人，请先在页面同步");
        }
        String seatCode = SeatCatalog.byName(seatName).map(SeatCatalog.Column::code).orElse("");
        if (seatCode.isEmpty()) {
            return BookingResult.of(Outcome.ERROR, "未知席别代码：" + seatName);
        }

        try {
            String rejected = client.submitOrderRequest(train, trainDate);
            if (rejected != null) {
                return BookingResult.of(Outcome.NO_TICKET, "提交订单被拒：" + rejected);
            }
            BookingClient.Tokens tokens = client.initDc();
            String[] strings = BookingClient.buildPassengerStrings(seatCode, chosen);
            BookingClient.StepResult check = client.checkOrderInfo(tokens, strings[0], strings[1]);
            if (!check.ok()) {
                return BookingResult.of(Outcome.NO_TICKET, "校验未通过：" + check.message());
            }
            String queueProblem = client.getQueueCount(tokens, train, trainDate, seatCode, tokens.leftTicketStr());
            if (queueProblem != null) {
                return BookingResult.of(Outcome.QUEUE_FULL, queueProblem);
            }
            BookingClient.StepResult confirm = client.confirmSingleForQueue(tokens, strings[0], strings[1]);
            if (!confirm.ok()) {
                return BookingResult.of(Outcome.ERROR, "确认提交失败：" + confirm.message());
            }
            BookingClient.BookingOutcome outcome = client.awaitOrder(tokens, AWAIT_MS);
            if (outcome.success()) {
                return BookingResult.success(outcome.orderNo(),
                        "抢到 " + train.trainNo() + " " + seatName + "，请在 30 分钟内付款");
            }
            return BookingResult.of(Outcome.ERROR, outcome.message());
        } catch (TicketClient.RedirectedException e) {
            return BookingResult.of(Outcome.RISK, "被风控或会话失效：" + e.getMessage());
        } catch (IOException e) {
            return BookingResult.of(Outcome.ERROR, "下单异常：" + e.getMessage());
        }
    }

    /** 按任务勾选的乘车人顺序取，票不够就买前 N 个（部分满足）。 */
    private List<PassengerSync.Remote> resolvePassengers(Store.TaskRow task, SeatInfo seat)
            throws IOException {
        List<Store.PassengerRow> local = store.findPassengers(com.jactil.tickethelper.web.AppContext.ACCOUNT_ID);
        List<String> wantedPids = new ArrayList<>();
        for (Long id : task.passengerIds()) {
            local.stream().filter(p -> p.id() == id).findFirst()
                    .ifPresent(p -> wantedPids.add(p.pid()));
        }
        if (wantedPids.isEmpty()) {
            local.forEach(p -> wantedPids.add(p.pid()));
        }
        List<PassengerSync.Remote> remote = passengers.fetch();
        List<PassengerSync.Remote> chosen = new ArrayList<>();
        for (String pid : wantedPids) {
            remote.stream().filter(r -> r.pid().equals(pid)).findFirst().ifPresent(chosen::add);
        }
        int capacity = seat.count() > 0 ? seat.count() : chosen.size();
        return chosen.size() > capacity ? chosen.subList(0, capacity) : chosen;
    }

    static String toIsoDate(String raw) {
        if (raw == null || raw.length() != 8) {
            return null;
        }
        try {
            return LocalDate.parse(raw, RAW_DATE).toString();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
