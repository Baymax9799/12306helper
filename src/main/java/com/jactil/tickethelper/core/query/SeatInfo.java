package com.jactil.tickethelper.core.query;

/**
 * 一个席别的余票。
 *
 * <p>{@code count} 的取值约定：
 * <ul>
 *   <li>{@code > 0}：确切的余票张数</li>
 *   <li>{@code 0}：售完（原文 "无"）</li>
 *   <li>{@code -1}：有票但没给张数（原文 "有"）</li>
 *   <li>{@code -2}：该车次不提供此席别（原文字段为空）</li>
 * </ul>
 */
public record SeatInfo(String code, String name, int count, String raw) {

    public static final int ABSENT = -2;
    public static final int UNKNOWN_AVAILABLE = -1;

    public boolean exists() {
        return count != ABSENT;
    }

    public boolean available() {
        return count != ABSENT && count != 0;
    }

    /** 是否够买 n 张。 */
    public boolean enough(int n) {
        if (!exists() || count == 0) {
            return false;
        }
        return count == UNKNOWN_AVAILABLE || count >= n;
    }
}
