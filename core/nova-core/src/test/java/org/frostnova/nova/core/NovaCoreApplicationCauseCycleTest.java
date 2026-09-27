package org.frostnova.nova.core;

import org.frostnova.nova.core.util.SpinGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动失败的处理沿起因链认配置错、取最内层：起因链成环时不能原地打转
 *
 * <h2>抓的是哪件用户故障</h2>
 * 起因链成环（A 的起因是 B、B 的起因又是 A）的异常一进启动失败的处理，那两处沿起因链
 * 逐层走的循环就原地打转：配置错认不出来、最内层取不到，安全模式起不来、进程也不退出，
 * 远程部署的使用者连配置界面都进不去。
 *
 * <h2>为什么带时限</h2>
 * 打转的循环拦不住中断，超时注解会连测试线程一起挂住。每格都经时间盒跑，
 * 打转判红而不是把整盘挂住。正常三层起因链的格是阴面：链是有限的，照旧一层层走到底。
 *
 * <h2>为什么从这两个口进去</h2>
 * 两处都是私有静态方法，整条路一进去就要真起安全模式的端口。仓内直叫私有方法是既有写法
 * （配置界面那几件同形），签名编译期对不上当场就红，不算认名字。
 */
@DisplayName("启动失败的处理遇起因链成环")
class NovaCoreApplicationCauseCycleTest {

    /**
     * 名字尾巴对着「按名认配置错」那一支造的假异常
     * <p>
     * 生产认配置错其中一支就是看类名以 {@code ConfigDataResourceNotFoundException} 结尾、
     * 不看类型——假件只要名字对得上，不必把 Spring 的那套搬进格里。
     */
    private static final class StubConfigDataResourceNotFoundException extends Exception {
        StubConfigDataResourceNotFoundException(String message) {
            super(message);
        }
    }

    @Test
    @DisplayName("认配置错：成环时走过的一层不再走，不打转")
    void configurationCheckStopsWhenCauseChainFormsACycle() throws Exception {
        Exception outer = new IllegalStateException("环外");
        Exception ring = new IllegalArgumentException("环内");
        outer.initCause(ring);
        ring.initCause(outer);

        // 环上没有配置错：绕回来该停手判否，不是绕着找
        assertFalse(SpinGuard.stopsWithin(SpinGuard.STOP_MILLIS, () -> isConfigurationFailure(outer)),
                "环上没有配置错，判否就该走完回来");

        // 阳面：环上那一层正是配置错，走到它就该判是
        Exception matchOuter = new Exception("环外");
        Exception matchRing = new StubConfigDataResourceNotFoundException("环内");
        matchOuter.initCause(matchRing);
        matchRing.initCause(matchOuter);

        assertTrue(SpinGuard.stopsWithin(SpinGuard.STOP_MILLIS, () -> isConfigurationFailure(matchOuter)),
                "环上那一层是配置错，判是才对");
    }

    @Test
    @DisplayName("认配置错：正常三层起因链照旧走到底")
    void configurationCheckStillWalksThreeLayers() throws Exception {
        Exception outer = new IllegalStateException("外层");
        Exception middle = new IllegalStateException("中间");
        Exception inner = new StubConfigDataResourceNotFoundException("最里");
        outer.initCause(middle);
        middle.initCause(inner);

        assertTrue(SpinGuard.stopsWithin(SpinGuard.STOP_MILLIS, () -> isConfigurationFailure(outer)),
                "配置错在第三层，一层层走下去该认出来");

        // 阴性对照：三层都不是配置错时判否，认的不是「有个起因」就算
        Exception outer2 = new IllegalStateException("外层");
        Exception middle2 = new IllegalStateException("中间");
        Exception inner2 = new UnsupportedOperationException("最里");
        outer2.initCause(middle2);
        middle2.initCause(inner2);

        assertFalse(SpinGuard.stopsWithin(SpinGuard.STOP_MILLIS, () -> isConfigurationFailure(outer2)),
                "三层都不是配置错，判否才对");
    }

    @Test
    @DisplayName("取最内层：成环时停在环上最后一个没走过的那层，不打转")
    void describeStopsWhenCauseChainFormsACycle() throws Exception {
        Exception outer = new IllegalStateException("环外");
        Exception ring = new IllegalArgumentException("环内");
        outer.initCause(ring);
        ring.initCause(outer);

        String described = SpinGuard.stopsWithin(SpinGuard.STOP_MILLIS, () -> describe(outer));

        // 环上最后一个没走过的那一层是 ring：绕回 outer 就该停，取的是 ring 不是 outer
        assertEquals("IllegalArgumentException: 环内", described);
    }

    @Test
    @DisplayName("取最内层：正常三层起因链照旧走到底")
    void describeStillWalksThreeLayers() throws Exception {
        Exception outer = new IllegalStateException("外层");
        Exception middle = new IllegalArgumentException("中间");
        Exception inner = new UnsupportedOperationException("最里");
        outer.initCause(middle);
        middle.initCause(inner);

        String described = SpinGuard.stopsWithin(SpinGuard.STOP_MILLIS, () -> describe(outer));

        // 三层三个类型：取的是最里那一层，不是外层换件衣裳
        assertEquals("UnsupportedOperationException: 最里", described);
    }

    /**
     * 直叫认配置错的私有方法
     */
    private static boolean isConfigurationFailure(Throwable failure) throws Exception {
        Method method = NovaCoreApplication.class.getDeclaredMethod("isConfigurationFailure", Throwable.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, failure);
    }

    /**
     * 直叫取最内层的私有方法
     */
    private static String describe(Throwable failure) throws Exception {
        Method method = NovaCoreApplication.class.getDeclaredMethod("describe", Throwable.class);
        method.setAccessible(true);
        return (String) method.invoke(null, failure);
    }
}
