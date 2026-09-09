package org.frostnova.nova.adapter.onebot.extension.napcat.aop;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.adapter.onebot.extension.napcat.util.NapcatServiceHolder;
import org.frostnova.nova.adapter.onebot.model.OneBotSender;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Pointcut;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 认出对面是不是 NapCat
 *
 * <h2>这一格为什么连切点串一起钉</h2>
 * 这条服务发现整个挂在「体检时顺路问一句版本」上：切点串写错、方法改名、
 * 版本接口哪天不再被调用，名单就永远是空的——而名单空了<b>没有任何现象</b>，
 * 只是 @全体成员 次数用完那天不再自动转群待办。所以除了行为，
 * 也把它拦在哪个方法上一并钉住。
 *
 * <h2>放行的判据是「原样回」</h2>
 * 这是个环绕通知，它无论认不认得出对面，都必须把版本接口的原返回值原封不动交回去；
 * 一旦它开始加工返回值，体检那一侧的判断就建在了本扩展的实现细节上。
 */
@DisplayName("NapCat 服务发现切面")
class NapCatServiceDiscoveryAspectTest {
    private NapcatServiceHolder holder;

    private NapCatServiceDiscoveryAspect aspect;

    private final OneBotSender sender = sender();

    private static OneBotSender sender() {
        OneBotSender sender = new OneBotSender();
        sender.setName("napcat-qq");
        return sender;
    }

    @BeforeEach
    void setUp() {
        holder = new NapcatServiceHolder();
        aspect = new NapCatServiceDiscoveryAspect(holder);
    }

    /**
     * 造一次「体检问版本」的调用，让被拦住的那一层回给定的报文
     */
    private ProceedingJoinPoint versionInfoReturning(String json) throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getArgs()).thenReturn(new Object[]{sender, new JSONObject()});
        when(joinPoint.proceed()).thenReturn(json == null ? null : JSON.parseObject(json));
        return joinPoint;
    }

    @Test
    @DisplayName("app_name 里带 NapCat 时登记进名单, 版本报文原样回")
    void registersNapcat() throws Throwable {
        ProceedingJoinPoint joinPoint = versionInfoReturning("{\"app_name\":\"NapCat.Onebot\",\"app_version\":\"4.8.9\"}");

        Object result = aspect.aroundSendMethod(joinPoint);

        assertTrue(holder.isNapcat("napcat-qq"));
        assertSame(sender, holder.getNapcat("napcat-qq"));
        assertEquals("{\"app_name\":\"NapCat.Onebot\",\"app_version\":\"4.8.9\"}", ((JSONObject) result).toJSONString());
    }

    @Test
    @DisplayName("别家 OneBot 实现不登记, 版本报文照样原样回")
    void otherImplementationIsNotRegistered() throws Throwable {
        ProceedingJoinPoint joinPoint = versionInfoReturning("{\"app_name\":\"Lagrange.OneBot\",\"app_version\":\"1.0\"}");

        Object result = aspect.aroundSendMethod(joinPoint);

        assertFalse(holder.isNapcat("napcat-qq"));
        assertEquals("{\"app_name\":\"Lagrange.OneBot\",\"app_version\":\"1.0\"}", ((JSONObject) result).toJSONString());
    }

    @Test
    @DisplayName("报文里没有 app_name 时不登记, 原样回")
    void missingAppNameIsNotRegistered() throws Throwable {
        ProceedingJoinPoint joinPoint = versionInfoReturning("{\"app_version\":\"1.0\"}");

        Object result = aspect.aroundSendMethod(joinPoint);

        assertFalse(holder.isNapcat("napcat-qq"));
        assertEquals("{\"app_version\":\"1.0\"}", ((JSONObject) result).toJSONString());
    }

    @Test
    @DisplayName("版本接口回 null 时不登记, 也照样回 null")
    void nullResultIsNotRegistered() throws Throwable {
        ProceedingJoinPoint joinPoint = versionInfoReturning(null);

        assertNull(aspect.aroundSendMethod(joinPoint));
        assertFalse(holder.isNapcat("napcat-qq"));
    }

    @Test
    @DisplayName("认名字是子串匹配, 带前后缀的 NapCat 也认得出")
    void appNameIsMatchedAsSubstring() throws Throwable {
        ProceedingJoinPoint joinPoint = versionInfoReturning("{\"app_name\":\"my-NapCat-fork\"}");

        aspect.aroundSendMethod(joinPoint);

        assertTrue(holder.isNapcat("napcat-qq"));
    }

    @Test
    @DisplayName("⚠️ 认名字区分大小写, napcat 全小写认不出")
    void appNameIsCaseSensitive() throws Throwable {
        ProceedingJoinPoint joinPoint = versionInfoReturning("{\"app_name\":\"napcat\"}");

        aspect.aroundSendMethod(joinPoint);

        assertFalse(holder.isNapcat("napcat-qq"));
    }

    @Test
    @DisplayName("切面拦的是版本接口那一支, 切点串一字不改")
    void pointcutStaysOnGetVersionInfo() throws Exception {
        Pointcut pointcut = NapCatServiceDiscoveryAspect.class
                .getDeclaredMethod("getVersionInfoMethod").getAnnotation(Pointcut.class);

        assertEquals("execution(* org.frostnova.nova.adapter.onebot.http.OneBotHttpAdapter.getVersionInfo(..))",
                pointcut.value());
    }
}
