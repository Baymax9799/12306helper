package com.jactil.tickethelper.engine;

import com.jactil.tickethelper.core.query.TrainRow;
import com.jactil.tickethelper.core.store.Store;

/** 只监控、不下单。默认行为，避免一上线就动钱。 */
public final class DisabledBooking implements BookingAttempt {

    @Override
    public BookingResult attempt(Store.TaskRow task, TrainRow train, String seatName) {
        return BookingResult.of(Outcome.DISABLED, "未开启自动下单");
    }
}
