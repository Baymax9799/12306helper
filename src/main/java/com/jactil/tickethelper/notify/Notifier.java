package com.jactil.tickethelper.notify;

/** 一个通知渠道。实现类自己处理配置读取与协议细节。 */
public interface Notifier {

    String name();

    boolean enabled();

    void send(String title, String body) throws Exception;
}
