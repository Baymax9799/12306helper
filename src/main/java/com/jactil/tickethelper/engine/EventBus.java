package com.jactil.tickethelper.engine;

import com.jactil.tickethelper.core.store.Store;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 事件总线：落库给前端轮询，同时扇出给通知模块。
 * 刻意不用 SSE：7×24 长连接是最容易泄漏的地方。
 */
public final class EventBus {

    public record Event(String type, Long taskId, String level, String message, Instant at) {
    }

    private final Store store;
    private final List<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();

    public EventBus(Store store) {
        this.store = store;
    }

    public void subscribe(Consumer<Event> listener) {
        listeners.add(listener);
    }

    public void emit(String type, Long taskId, String level, String message) {
        Event event = new Event(type, taskId, level, message, Instant.now());
        store.appendEvent(taskId, level, message);
        for (Consumer<Event> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                // 一个通知渠道出错不能影响抓票
            }
        }
    }

    public void info(Long taskId, String message) {
        emit("LOG", taskId, "INFO", message);
    }

    public void warn(Long taskId, String message) {
        emit("LOG", taskId, "WARN", message);
    }

    public void error(Long taskId, String message) {
        emit("LOG", taskId, "ERROR", message);
    }
}
