package com.starlwr.bot.core.config.ui.napcat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 续登层的防抖
 *
 * <h2>为什么要真的把那段 JS 跑起来</h2>
 * 要证的命题是「检测到落在登录页时至多自动续登一次，仍回登录页就停手」。
 * 🔴 <b>一段代码会不会循环，只有喂给它一串输入跑一遍才知道</b> ——
 * grep 能看出那里写了个判断，看不出那个判断拦不拦得住第二次。
 * 所以这里加载的是<b>真正交付的那个文件</b>，不是照它抄的一份 Java 翻版：
 * 翻版会在原文改坏时照样绿。
 *
 * <h2>为什么那段逻辑是纯函数</h2>
 * 决定（该不该续登）与照做（fetch、写 localStorage、重载 iframe）是分开的。
 * 只有前者是纯的，才跑得动 —— 后者要一个浏览器。
 * 分开还有一个附带好处：<b>会循环的只可能是决定那一半</b>，照做那一半没有回路。
 */
@DisplayName("NapCat 续登防抖")
class NapCatResumeDebounceTest {
    private static final String LOGIN_PATH = "/config/napcat/webui/web_login";

    private static final String NORMAL_PATH = "/config/napcat/webui/logs";

    private static ScriptEngine engine;

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("build.sh")) && Files.exists(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未能定位仓库根目录");
    }

    @BeforeAll
    static void loadTheRealScript() throws ScriptException {
        Path script = repositoryRoot()
                .resolve("starbot-core/src/main/resources/config-ui/napcat-resume.js");
        String source;
        try {
            source = Files.readString(script, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        engine = new org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory()
                .getScriptEngine("--language=es6");
        engine.eval(source);
    }

    /**
     * 判据自己先得能跑。<b>「一个断言都没红」与「脚本压根没加载」长得一模一样</b>。
     */
    @Test
    @DisplayName("判据自己先能把那段脚本加载起来")
    void theRulerLoadsTheRealScript() throws Exception {
        assertNotNull(engine.eval("NapCatResume"), "脚本没加载起来，下面所有判据都是恒真绿");
        assertEquals("web_login", engine.eval("NapCatResume.LOGIN_ROUTE"));
    }

    /**
     * 单步，并允许在两步之间改状态（模拟那次重载落地、模拟 load 事件）
     */
    private String step(String path) {
        try {
            Object action = ((Invocable) engine).invokeMethod(engine.eval("NapCatResume"), "step",
                    engine.eval("__state"), path);
            return String.valueOf(action);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void fresh() {
        try {
            engine.eval("var __state = NapCatResume.newState();");
        } catch (ScriptException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 模拟那次重载落地了（浏览器里由 iframe 的 load 事件清掉这个位）
     */
    private void reloadLanded() {
        try {
            engine.eval("__state.reloading = false;");
        } catch (ScriptException e) {
            throw new IllegalStateException(e);
        }
    }

    @Nested
    @DisplayName("至多一次")
    class AtMostOnce {
        /**
         * 🔴 这就是要红一次的那一条。
         * 桩发的凭据必然无效 —— 表现成「换完还是回到登录页」——
         * 见证它<b>恰好停在一次</b>，不循环。
         * 把 {@code decide} 里那句 {@code if (state.renewed) return GIVE_UP} 删掉，
         * 这条立刻红：五十步会数出五十次 RENEW。
         */
        @Test
        @DisplayName("凭据换了也没用时，恰好续登一次然后停手")
        void renewsExactlyOnceThenStops() {
            fresh();
            List<String> actions = new ArrayList<>();

            actions.add(step(LOGIN_PATH));      // 第一次看见登录页 → 该续登
            reloadLanded();                     // 那把凭据是无效的，重载完还在登录页
            for (int i = 0; i < 50; i++) {
                actions.add(step(LOGIN_PATH));
                reloadLanded();
            }

            long renews = actions.stream().filter("RENEW"::equals).count();
            assertEquals(1, renews, "至多续登一次（边界⑤）。实际的决定序列: " + actions);
            assertEquals("RENEW", actions.get(0), "第一次看见登录页应当续登");
            assertEquals("GIVE_UP", actions.get(1), "换过一次还在登录页，就该停手");
            assertTrue(actions.subList(2, actions.size()).stream().allMatch("WAIT"::equals),
                    "停手之后应当一直什么都不做，实际: " + actions);
        }

        /**
         * 续登之后那次重载还在路上时不许判。
         * <b>不等它落地就判，会把「正在救」误判成「救不回来」</b>，于是一次机会都不给。
         */
        @Test
        @DisplayName("续登后的重载还没落地时，不下判断")
        void waitsForTheReloadToLand() {
            fresh();
            assertEquals("RENEW", step(LOGIN_PATH));
            assertEquals("WAIT", step(LOGIN_PATH), "重载还没落地就判了");
            assertEquals("WAIT", step(LOGIN_PATH));

            reloadLanded();
            assertEquals("GIVE_UP", step(LOGIN_PATH));
        }

        /**
         * 归零的条件是「确实离开过登录页」，不是「过了多久」。
         * 按时间归零的话，一个一直卡在登录页的内层会被反复重试 —— 那正是要防的循环。
         */
        @Test
        @DisplayName("救回来之后，下一次失效还能再救一次")
        void resetsOnlyAfterActuallyLeavingTheLoginPage() {
            fresh();
            assertEquals("RENEW", step(LOGIN_PATH));
            reloadLanded();
            assertEquals("RESET", step(NORMAL_PATH), "回到正常页面，这一轮结束");
            assertEquals("RENEW", step(LOGIN_PATH), "一小时后再失效，应当还能救一次");
        }

        @Test
        @DisplayName("读不到内层路径时什么都不做")
        void doesNothingWhenTheInnerPathIsUnreadable() {
            fresh();
            assertEquals("WAIT", step(null));
            assertEquals("WAIT", step(null));
            assertEquals("RENEW", step(LOGIN_PATH), "读得到之后照常工作");
        }
    }

    @Nested
    @DisplayName("认路径")
    class PathMatching {
        private boolean isLogin(String path) {
            try {
                return Boolean.TRUE.equals(((Invocable) engine)
                        .invokeMethod(engine.eval("NapCatResume"), "isLoginPath", path));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Test
        @DisplayName("认得出真实的登录路径")
        void recognisesTheRealLoginPath() {
            assertTrue(isLogin(LOGIN_PATH));
            assertTrue(isLogin("/config/napcat/webui/web_login/"), "尾斜杠");
            assertTrue(isLogin("/config/napcat/webui/web_login?token=x"), "带查询串");
        }

        /**
         * 🔴 不许用子串。这个项目里子串匹配已经栽过四次
         * （最近一次是数动态 id 时把 {@code rid=} 也吃了进去）。
         * 用子串的话，NapCat 哪天加一个 {@code /web_login_history}，
         * 这里就会对着一个正常页面反复换凭据。
         */
        @Test
        @DisplayName("不把包含它的别的路径当成登录页")
        void doesNotMatchBySubstring() {
            assertFalse(isLogin("/config/napcat/webui/web_login_history"));
            assertFalse(isLogin("/config/napcat/webui/logs?from=web_login"));
            assertFalse(isLogin("/config/napcat/webui/web_login/detail"));
            assertFalse(isLogin("/config/napcat/webui/logs"));
            assertFalse(isLogin(""));
            assertFalse(isLogin(null));
        }
    }
}
