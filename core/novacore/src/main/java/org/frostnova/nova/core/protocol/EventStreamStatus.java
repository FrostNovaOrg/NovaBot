package org.frostnova.nova.core.protocol;

import java.util.HashSet;
import java.util.Set;

/**
 * 事件输出此刻的运行情况，供控制台连接页那张外部面板卡显示
 * <p>
 * 只记两件事：事件输出是在跑、没开，还是因页面来源配置格式不合而暂停；
 * 以及最近一次被拒的、没登记过的浏览器页面来源。两件都只在日志里出现过一次的话，
 * 使用者在控制台上看到的是「一切正常」，而浏览器面板就是连不上。
 */
public class EventStreamStatus {
    /**
     * 同一次运行里按来源去重时最多记住几个来源。超过之后新来源照常拒、照常显示在卡上，只是不再逐个写日志
     */
    static final int ORIGIN_LIMIT = 32;

    /** 事件输出的三种状态 */
    public enum State {
        /** 配置里没开 */
        OFF,
        /** 正在接受连接 */
        RUNNING,
        /** 页面来源配置格式不合，事件输出暂停，程序其余部分照常 */
        PAUSED
    }

    private volatile State state = State.OFF;

    private volatile String unlistedOrigin;

    /** 只在 {@link #unlisted} 里、持本对象的锁读写：数量判断与加入得是一步，几路同时来也不超上限 */
    private final Set<String> loggedOrigins = new HashSet<>();

    public State state() {
        return state;
    }

    /**
     * @return 最近一次来连而没登记的页面来源；本次运行还没有过时为 {@code null}
     */
    public String unlistedOrigin() {
        return unlistedOrigin;
    }

    void state(State value) {
        state = value;
    }

    /**
     * 记下一次没登记的页面来源来连
     * @param origin 规整后的页面来源
     * @return 该来源本次运行头一回出现、需要写一条日志时为 {@code true}
     */
    synchronized boolean unlisted(String origin) {
        unlistedOrigin = origin;
        return loggedOrigins.size() < ORIGIN_LIMIT && loggedOrigins.add(origin);
    }
}
