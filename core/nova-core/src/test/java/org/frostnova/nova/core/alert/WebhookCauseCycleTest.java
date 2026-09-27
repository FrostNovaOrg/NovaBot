package org.frostnova.nova.core.alert;

import org.frostnova.nova.core.util.SpinGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLException;
import java.lang.reflect.Method;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Webhook 发送失败的收尾沿起因链找原因：起因链成环时不能原地打转
 *
 * <h2>抓的是哪件用户故障</h2>
 * 起因链成环（A 的起因是 B、B 的起因又是 A）的异常一进发送失败的收尾，找最深一层与
 * 逐层找指定类型这两处循环就原地打转——告警这一路卡死在那里，消息发不出去。
 * 告警发不出去的那一刻，恰恰是最需要收到通知的时刻（QQ 掉线、机器人掉登录）。
 *
 * <h2>为什么带时限</h2>
 * 打转的循环拦不住中断，超时注解会连测试线程一起挂住。每格都经时间盒跑，
 * 打转判红而不是把整盘挂住。正常三层起因链的格是阴面：链是有限的，照旧一层层走到底。
 *
 * <h2>为什么从这两个口进去</h2>
 * 两处都是私有静态方法，发送的整条路要先过剥地址那一手（别的收尾管），成环的异常一进
 * 整条路就先死在别处，量不到这两处自己的打转。仓内直叫私有方法是既有写法
 * （配置界面那几件同形），签名编译期对不上当场就红，不算认名字。
 */
@DisplayName("Webhook 发送失败的收尾遇起因链成环")
class WebhookCauseCycleTest {

    @Test
    @DisplayName("找最深一层：成环时停在环上最后一个没走过的那层，不打转")
    void reasonOfStopsWhenCauseChainFormsACycle() throws Exception {
        Exception outer = new IllegalStateException("环外");
        Exception ring = new IllegalArgumentException("环内");
        outer.initCause(ring);
        ring.initCause(outer);

        String reason = SpinGuard.stopsWithin(SpinGuard.STOP_MILLIS, () -> reasonOf(outer));

        // 环上最后一个没走过的那一层是 ring：绕回 outer 就该停，不是绕几圈再说
        assertEquals("发送失败（IllegalArgumentException）", reason);
    }

    @Test
    @DisplayName("找最深一层：正常三层起因链照旧走到底")
    void reasonOfStillWalksThreeLayers() throws Exception {
        Exception outer = new IllegalStateException("外层");
        Exception middle = new IllegalStateException("中间");
        Exception inner = new UnsupportedOperationException("最里");
        outer.initCause(middle);
        middle.initCause(inner);

        String reason = SpinGuard.stopsWithin(SpinGuard.STOP_MILLIS, () -> reasonOf(outer));

        // 三层三个类型：报的是最里那一层，不是外层换件衣裳
        assertEquals("发送失败（UnsupportedOperationException）", reason);

        // 起因在第三层时按类型的支路还找得到，不是一律「发送失败」
        Exception timeoutOuter = new IllegalStateException("外层");
        Exception timeoutMiddle = new IllegalStateException("中间");
        SocketTimeoutException timeoutInner = new SocketTimeoutException("最里");
        timeoutOuter.initCause(timeoutMiddle);
        timeoutMiddle.initCause(timeoutInner);

        String timeoutReason = SpinGuard.stopsWithin(SpinGuard.STOP_MILLIS, () -> reasonOf(timeoutOuter));

        assertEquals("超时", timeoutReason);
    }

    @Test
    @DisplayName("逐层找类型：成环时走过的一层不再走，不打转")
    void findCauseStopsWhenCauseChainFormsACycle() throws Exception {
        Exception outer = new IllegalStateException("环外");
        Exception ring = new IllegalArgumentException("环内");
        outer.initCause(ring);
        ring.initCause(outer);

        // 阳面：环上那一层正是要找的类型，走到它就该找到
        Exception matchOuter = new Exception("环外");
        SocketTimeoutException matchRing = new SocketTimeoutException("环内");
        matchOuter.initCause(matchRing);
        matchRing.initCause(matchOuter);

        assertNull(SpinGuard.stopsWithin(SpinGuard.STOP_MILLIS, () -> findCause(outer, SSLException.class)),
                "环上没有要找的类型，绕回来该停手回 null");
        assertSame(matchRing,
                SpinGuard.stopsWithin(SpinGuard.STOP_MILLIS, () -> findCause(matchOuter, SocketTimeoutException.class)));
    }

    @Test
    @DisplayName("逐层找类型：正常三层起因链照旧走到底")
    void findCauseStillWalksThreeLayers() throws Exception {
        Exception outer = new IllegalStateException("外层");
        Exception middle = new IllegalStateException("中间");
        SSLException inner = new SSLException("最里");
        outer.initCause(middle);
        middle.initCause(inner);

        assertSame(inner, SpinGuard.stopsWithin(SpinGuard.STOP_MILLIS, () -> findCause(outer, SSLException.class)));

        Exception outer2 = new IllegalStateException("外层");
        Exception middle2 = new IllegalStateException("中间");
        Exception inner2 = new UnsupportedOperationException("最里");
        outer2.initCause(middle2);
        middle2.initCause(inner2);

        assertNull(SpinGuard.stopsWithin(SpinGuard.STOP_MILLIS, () -> findCause(outer2, SSLException.class)),
                "三层都不是那个类型时照旧回 null");
    }

    /**
     * 直叫取原因那一句的私有方法
     */
    private static String reasonOf(Exception e) throws Exception {
        Method method = WebhookAlertChannel.class.getDeclaredMethod("reasonOf", Exception.class);
        method.setAccessible(true);
        return (String) method.invoke(null, e);
    }

    /**
     * 直叫逐层找类型的私有方法
     */
    private static <T extends Throwable> T findCause(Throwable e, Class<T> type) throws Exception {
        Method method = WebhookAlertChannel.class.getDeclaredMethod("findCause", Throwable.class, Class.class);
        method.setAccessible(true);
        return type.cast(method.invoke(null, e, type));
    }
}
