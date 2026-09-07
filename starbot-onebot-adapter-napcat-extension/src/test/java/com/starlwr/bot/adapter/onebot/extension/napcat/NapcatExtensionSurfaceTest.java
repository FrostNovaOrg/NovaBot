package com.starlwr.bot.adapter.onebot.extension.napcat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.adapter.onebot.extension.napcat.annotation.NapcatApi;
import com.starlwr.bot.adapter.onebot.extension.napcat.aop.BackupAtAllAspect;
import com.starlwr.bot.adapter.onebot.extension.napcat.aop.NapCatServiceDiscoveryAspect;
import com.starlwr.bot.adapter.onebot.extension.napcat.config.NapcatHttpAdapterRegistrar;
import com.starlwr.bot.adapter.onebot.extension.napcat.config.OneBotAdapterNapcatExtensionPluginProperties;
import com.starlwr.bot.adapter.onebot.extension.napcat.http.NapcatHttpAdapter;
import com.starlwr.bot.adapter.onebot.extension.napcat.http.NapcatHttpAdapterProxy;
import com.starlwr.bot.adapter.onebot.extension.napcat.util.NapcatServiceHolder;
import com.starlwr.bot.adapter.onebot.model.OneBotSender;
import com.starlwr.bot.core.config.ConfigEffect;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.util.HttpUtil;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 本扩展对外露出的那一层：接口地址、装配方式、配置键
 *
 * <h2>为什么这些也要立格</h2>
 * 这个模块几乎没有自己的业务，它的价值全在<b>接得上</b>：注解读得到、代理装得起来、
 * 配置键与别处对得上。这几样都是<b>坏了不出声</b>的东西——注解丢了 retention，
 * 代理就退化成「所有方法都不支持」；@Bean 方法改个名，容器里就多一个没人注入的 bean，
 * 而旧名字那个位置从此空着。它们不属于任何一条业务路径，所以只能单独钉。
 */
@DisplayName("NapCat 扩展对外露面")
class NapcatExtensionSurfaceTest {
    @Nested
    @DisplayName("接口注解")
    class ApiAnnotation {
        @Test
        @DisplayName("注解活到运行期, 只能标在方法上")
        void annotationIsReadableAtRuntime() {
            assertEquals(RetentionPolicy.RUNTIME, NapcatApi.class.getAnnotation(Retention.class).value());
            assertArrayEquals(new ElementType[]{ElementType.METHOD}, NapcatApi.class.getAnnotation(Target.class).value());
        }

        @Test
        @DisplayName("两支扩展接口的名称与地址一字不改")
        void apiNamesAndUrlsAreFixed() throws Exception {
            NapcatApi remain = api("getGroupAtAllRemain");
            assertEquals("获取 @全体成员 剩余次数", remain.name());
            assertEquals("/get_group_at_all_remain", remain.url());

            NapcatApi todo = api("setGroupTodo");
            assertEquals("设置群待办", todo.name());
            assertEquals("/set_group_todo", todo.url());
        }

        @Test
        @DisplayName("接口上只有这两支, 每一支都标了注解")
        void everyMethodIsAnnotated() {
            Method[] methods = NapcatHttpAdapter.class.getDeclaredMethods();

            assertEquals(2, methods.length);
            for (Method method : methods) {
                assertTrue(method.isAnnotationPresent(NapcatApi.class), method.getName() + " 没标 @NapcatApi");
                assertEquals(JSONObject.class, method.getReturnType());
                assertArrayEquals(new Class[]{OneBotSender.class, JSONObject.class}, method.getParameterTypes());
            }
        }

        private NapcatApi api(String methodName) throws Exception {
            return NapcatHttpAdapter.class
                    .getDeclaredMethod(methodName, OneBotSender.class, JSONObject.class)
                    .getAnnotation(NapcatApi.class);
        }
    }

    @Nested
    @DisplayName("装配")
    class Assembly {
        private final HttpUtil http = mock(HttpUtil.class);

        private final NapcatHttpAdapterRegistrar registrar = new NapcatHttpAdapterRegistrar();

        @Test
        @DisplayName("装出来的适配器是个动态代理, 背后就是交给它的那个处理器")
        void adapterIsBackedByTheGivenHandler() {
            NapcatHttpAdapterProxy handler = registrar.napcatHttpAdapterProxy(http);

            NapcatHttpAdapter adapter = registrar.napcatHttpAdapter(handler);

            assertTrue(Proxy.isProxyClass(adapter.getClass()));
            assertSame(handler, Proxy.getInvocationHandler(adapter));
        }

        @Test
        @DisplayName("装配出来的这一条真能打出请求去")
        void assembledAdapterReallyCallsOut() {
            when(http.postJson(anyString(), anyMap(), any()))
                    .thenReturn(JSON.parseObject("{\"retcode\":0,\"data\":{\"can_at_all\":false}}"));
            NapcatHttpAdapter adapter = registrar.napcatHttpAdapter(registrar.napcatHttpAdapterProxy(http));
            OneBotSender sender = new OneBotSender();
            sender.setOneBotAddress("10.0.0.1");
            sender.setOneBotHttpPort(6700);

            JSONObject data = adapter.getGroupAtAllRemain(sender, new JSONObject());

            assertEquals("{\"can_at_all\":false}", data.toJSONString());
        }

        /**
         * Spring 拿方法名当 bean 名。改名不会有任何报错，只会让按名字注入的那一处从此注不进来
         */
        @Test
        @DisplayName("两个 bean 的名字就是这两个方法名")
        void beanNamesAreTheMethodNames() throws Exception {
            Method proxy = NapcatHttpAdapterRegistrar.class.getDeclaredMethod("napcatHttpAdapterProxy", HttpUtil.class);
            Method adapter = NapcatHttpAdapterRegistrar.class.getDeclaredMethod("napcatHttpAdapter", NapcatHttpAdapterProxy.class);

            assertNotNull(proxy.getAnnotation(Bean.class));
            assertNotNull(adapter.getAnnotation(Bean.class));
            assertEquals(NapcatHttpAdapterProxy.class, proxy.getReturnType());
            assertEquals(NapcatHttpAdapter.class, adapter.getReturnType());
        }

        @Test
        @DisplayName("四个由容器托管的类都还挂着 StarBot 组件注解, 少一个就整条功能不上线")
        void componentsStayScanned() {
            assertTrue(NapcatHttpAdapterRegistrar.class.isAnnotationPresent(StarBotComponent.class));
            assertTrue(NapcatServiceHolder.class.isAnnotationPresent(StarBotComponent.class));
            assertTrue(BackupAtAllAspect.class.isAnnotationPresent(StarBotComponent.class));
            assertTrue(NapCatServiceDiscoveryAspect.class.isAnnotationPresent(StarBotComponent.class));

            assertTrue(BackupAtAllAspect.class.isAnnotationPresent(Aspect.class));
            assertTrue(NapCatServiceDiscoveryAspect.class.isAnnotationPresent(Aspect.class));
        }
    }

    @Nested
    @DisplayName("配置")
    class Properties {
        @Test
        @DisplayName("配置前缀一字不改")
        void prefixIsFixed() {
            ConfigurationProperties properties =
                    OneBotAdapterNapcatExtensionPluginProperties.class.getAnnotation(ConfigurationProperties.class);

            assertEquals("starbot.adapter.onebot.extension.napcat", properties.prefix());
            assertTrue(OneBotAdapterNapcatExtensionPluginProperties.class.isAnnotationPresent(Configuration.class));
            assertTrue(OneBotAdapterNapcatExtensionPluginProperties.class.isAnnotationPresent(StarBotComponent.class));
        }

        @Test
        @DisplayName("只有一项配置, 名字与默认值不变, 默认是开着的")
        void theOnlyKeyKeepsItsNameAndDefault() {
            Field[] fields = OneBotAdapterNapcatExtensionPluginProperties.class.getDeclaredFields();

            assertEquals(1, fields.length);
            assertEquals("enableBackupAtAll", fields[0].getName());
            assertEquals(boolean.class, fields[0].getType());
            assertTrue(new OneBotAdapterNapcatExtensionPluginProperties().isEnableBackupAtAll());
        }

        @Test
        @DisplayName("这一项是重启才生效, 界面据此告诉使用者要不要重启")
        void takesEffectOnRestart() throws Exception {
            ConfigEffect effect = OneBotAdapterNapcatExtensionPluginProperties.class
                    .getDeclaredField("enableBackupAtAll").getAnnotation(ConfigEffect.class);

            assertEquals(ConfigEffect.Effect.RESTART, effect.value());
        }

        @Test
        @DisplayName("读写这一项走的是这两个方法名")
        void accessorNamesAreFixed() {
            OneBotAdapterNapcatExtensionPluginProperties properties = new OneBotAdapterNapcatExtensionPluginProperties();

            properties.setEnableBackupAtAll(false);

            assertFalse(properties.isEnableBackupAtAll());
        }
    }
}
