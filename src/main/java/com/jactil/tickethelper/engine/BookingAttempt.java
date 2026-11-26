package com.jactil.tickethelper.engine;

import com.jactil.tickethelper.core.query.TrainRow;
import com.jactil.tickethelper.core.store.Store;

/**
 * 下单动作的抽象。TaskRunner 只关心「有没有抢到」，不关心怎么下单，
 * 这样监控模式（不做下单）和真下单可以分别注入、分别测试。
 */
public interface BookingAttempt {

    enum Outcome {
        /** 抢到订单号 */
        SUCCESS,
        /** 余票不够 / 被别人抢先 */
        NO_TICKET,
        /** 排队人数超过余票 */
        QUEUE_FULL,
        /** 会话失效或被风控 */
        RISK,
        /** 本次没开下单能力（只监控） */
        DISABLED,
        /** 其它错误 */
        ERROR
    }

    record BookingResult(Outcome outcome, String orderNo, String message) {
        public static BookingResult of(Outcome outcome, String message) {
            return new BookingResult(outcome, null, message);
        }

        public static BookingResult success(String orderNo, String message) {
            return new BookingResult(Outcome.SUCCESS, orderNo, message);
        }
    }

    BookingResult attempt(Store.TaskRow task, TrainRow train, String seatName);
}
