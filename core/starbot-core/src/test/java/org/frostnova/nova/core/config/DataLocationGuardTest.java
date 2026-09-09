package org.frostnova.nova.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行时数据落点守卫测试
 * <p>
 * 钉的是 #286 裁下的四条，一条一格：默认派生落树内必改写／显式配置落树内必拒启动／
 * 逃生阀带非空理由才放行且理由要进日志／<b>生产形状（目录上溯不到 {@code .git}）一字不改</b>。
 * <p>
 * 🔴 最后那条阴性格不是凑数的。守卫改错了方向的样子，与它「什么都没改」的样子，
 * 在只测前三条的测试里长得一模一样——<b>一把只会在该红的地方红的尺，还得证明它在该绿的地方是绿的</b>，
 * 否则「生产两条路径一字不改」这句就没有任何一格量过它。
 * <p>
 * 本测试只证<b>判断逻辑</b>。「日志里看得见改写、程序确实从新路径读到了」那一层，
 * 要把程序真起一次才证得了，单元测试顶不了那个位置。
 */
@DisplayName("运行时数据落点守卫")
class DataLocationGuardTest {
    @TempDir
    Path tmp;

    /**
     * 收集守卫写出的日志行，用来判「日志可证」那两条。
     */
    private final List<String> logs = new ArrayList<>();

    /**
     * 造一棵「工作树」：目录里放一个 {@code .git}。
     *
     * @param asFile true 时 {@code .git} 是文件（git worktree／子模块的形态），否则是目录
     */
    private Path worktree(String name, boolean asFile) throws IOException {
        Path root = Files.createDirectories(tmp.resolve(name));
        Path dotGit = root.resolve(".git");
        if (asFile) {
            Files.writeString(dotGit, "gitdir: /somewhere/else\n");
        } else {
            Files.createDirectories(dotGit);
        }
        return root;
    }

    private DataLocationGuard guard(Path cwd, Path home, Map<String, String> env) {
        DeferredLogFactory factory = supplier -> new NoOpLog(logs);
        return new DataLocationGuard(factory) {
            @Override
            String env(String name) {
                return env.get(name);
            }

            @Override
            String home() {
                return home.toString();
            }

            @Override
            Path cwd() {
                return cwd;
            }
        };
    }

    private MockEnvironment environment(String configured) {
        MockEnvironment env = new MockEnvironment();
        if (configured != null) {
            Map<String, Object> map = new HashMap<>();
            map.put(DataLocationGuard.KEY, configured);
            env.getPropertySources().addLast(new MapPropertySource("test", map));
        }
        return env;
    }

    @Test
    @DisplayName("① 默认派生的路径落在工作树内 —— 改写到开发落点，并在日志里说清改写前后")
    void defaultPathInsideWorktreeIsRewritten() throws IOException {
        Path tree = worktree("repo", false);
        Path home = Files.createDirectories(tmp.resolve("home"));
        MockEnvironment env = environment(null);

        guard(tree, home, Map.of()).postProcessEnvironment(env, null);

        Path expected = home.resolve(DataLocationGuard.DEV_DIR).resolve(DataLocationGuard.DEFAULT_PATH);
        assertEquals(expected.toString(), env.getProperty(DataLocationGuard.KEY),
                "默认落点在工作树内时必须被改写到开发落点");
        assertTrue(logs.stream().anyMatch(l -> l.contains("改写前：") && l.contains(tree.toString())),
                "日志里要看得见改写前的路径，否则「已改写」是一句无从复核的话");
        assertTrue(logs.stream().anyMatch(l -> l.contains("改写后：") && l.contains(expected.toString())),
                "日志里要看得见改写后的路径");
    }

    @Test
    @DisplayName("① 之二 —— .git 是文件（git worktree 检出的树）也算落在工作树内")
    void gitFileAlsoCountsAsWorktree() throws IOException {
        Path tree = worktree("linked", true);
        Path home = Files.createDirectories(tmp.resolve("home2"));
        MockEnvironment env = environment(null);

        guard(tree, home, Map.of()).postProcessEnvironment(env, null);

        assertEquals(home.resolve(DataLocationGuard.DEV_DIR).resolve(DataLocationGuard.DEFAULT_PATH).toString(),
                env.getProperty(DataLocationGuard.KEY),
                "只认 .git 目录的话，git worktree 出来的树会被读成「不在工作树内」");
    }

    @Test
    @DisplayName("② 显式配置的路径落在工作树内 —— 拒绝启动，不替人改主意")
    void explicitPathInsideWorktreeIsRefused() throws IOException {
        Path tree = worktree("repo", false);
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path explicit = tree.resolve("var").resolve("data.json");
        MockEnvironment env = environment(explicit.toString());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> guard(tree, home, Map.of()).postProcessEnvironment(env, null));

        assertTrue(e.getMessage().contains("拒绝启动"), "拒绝的理由要写在异常里");
        assertTrue(e.getMessage().contains(DataLocationGuard.ALLOW_ENV),
                "拒绝时要把逃生阀怎么按一并告诉人，否则只剩一堵墙");
        assertEquals(explicit.toString(), env.getProperty(DataLocationGuard.KEY),
                "拒绝这一支不许顺手改写配置值");
    }

    @Test
    @DisplayName("③ 逃生阀带非空理由 —— 放行，且理由必须进日志")
    void escapeHatchWithReasonPasses() throws IOException {
        Path tree = worktree("repo", false);
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path explicit = tree.resolve("data.json");
        MockEnvironment env = environment(explicit.toString());
        Map<String, String> vars = Map.of(
                DataLocationGuard.ALLOW_ENV, "1",
                DataLocationGuard.REASON_ENV, "一次性复现落盘顺序");

        guard(tree, home, vars).postProcessEnvironment(env, null);

        assertEquals(explicit.toString(), env.getProperty(DataLocationGuard.KEY), "放行即落原地，不改写");
        assertTrue(logs.stream().anyMatch(l -> l.contains("一次性复现落盘顺序")),
                "理由必须进日志——按过而日志里查不到为什么按的开关，等于没要求理由");
    }

    @Test
    @DisplayName("③ 之二 —— 开关按下但理由为空，按拒绝处理")
    void escapeHatchWithoutReasonStillRefused() throws IOException {
        Path tree = worktree("repo", false);
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path explicit = tree.resolve("data.json");

        for (String blankReason : new String[]{null, "", "   "}) {
            Map<String, String> vars = new HashMap<>();
            vars.put(DataLocationGuard.ALLOW_ENV, "1");
            if (blankReason != null) {
                vars.put(DataLocationGuard.REASON_ENV, blankReason);
            }
            assertThrows(IllegalStateException.class,
                    () -> guard(tree, home, vars).postProcessEnvironment(environment(explicit.toString()), null),
                    "理由为 " + (blankReason == null ? "缺省" : "「" + blankReason + "」") + " 时必须仍然拒绝");
        }
    }

    @Test
    @DisplayName("④ 阴性 —— 生产形状（上溯不到 .git）一字不改，落原地")
    void productionShapeUntouched() throws IOException {
        Path production = Files.createDirectories(tmp.resolve("opt").resolve("starbot"));
        Path home = Files.createDirectories(tmp.resolve("home"));

        MockEnvironment derived = environment(null);
        guard(production, home, Map.of()).postProcessEnvironment(derived, null);
        assertNull(derived.getProperty(DataLocationGuard.KEY),
                "生产形状下守卫不得往环境里塞任何东西——它一动，systemd 与容器那两条路径就变了");

        MockEnvironment explicit = environment("data.json");
        guard(production, home, Map.of()).postProcessEnvironment(explicit, null);
        assertEquals("data.json", explicit.getProperty(DataLocationGuard.KEY), "显式配置在生产形状下同样原样保留");

        assertTrue(logs.isEmpty(), "什么都没发生的时候不该有日志——恒亮的告警会训练人忽略它");
    }

    @Test
    @DisplayName("⑥ 注册 —— 守卫必须真的被 Spring 装上，而不是只写对了逻辑")
    void guardIsActuallyRegistered() throws Exception {
        String key = "org.springframework.boot.EnvironmentPostProcessor";
        java.util.List<String> declared = new ArrayList<>();
        var urls = getClass().getClassLoader().getResources("META-INF/spring.factories");
        while (urls.hasMoreElements()) {
            java.util.Properties props = new java.util.Properties();
            try (var in = urls.nextElement().openStream()) {
                props.load(in);
            }
            String v = props.getProperty(key);
            if (v != null) {
                for (String one : v.split(",")) {
                    declared.add(one.trim());
                }
            }
        }

        assertTrue(declared.contains(DataLocationGuard.class.getName()),
                "spring.factories 的 " + key + " 下没有列出守卫 —— 逻辑写对了但没装上，等于没有守卫。"
                        + "实有：" + declared);

        // 🔴 键名对了还不够：键名就是**接口的全限定名**，装错接口一样不会被调用。
        //    这两处正是本轮踩的：先用了 .imports 文件（Boot 4 认的是 spring.factories），
        //    又用了 org.springframework.boot.env.EnvironmentPostProcessor（两个同名接口都在）。
        //    单元测试当时 6 格全绿，因为它们**只测了类自己的逻辑，没测它有没有被装上**。
        assertTrue(Class.forName(key).isAssignableFrom(DataLocationGuard.class),
                "守卫没有实现 " + key + " —— 键名与接口必须是同一个");
    }

    /**
     * 只把消息收进列表的 {@code Log}。
     * <p>
     * 守卫跑在日志系统初始化之前，Boot 给的是延迟日志；这里只需要看它说了什么。
     */
    private record NoOpLog(List<String> sink) implements org.apache.commons.logging.Log {
        @Override public boolean isFatalEnabled() { return true; }
        @Override public boolean isErrorEnabled() { return true; }
        @Override public boolean isWarnEnabled() { return true; }
        @Override public boolean isInfoEnabled() { return true; }
        @Override public boolean isDebugEnabled() { return true; }
        @Override public boolean isTraceEnabled() { return true; }
        @Override public void fatal(Object message) { sink.add(String.valueOf(message)); }
        @Override public void fatal(Object message, Throwable t) { sink.add(String.valueOf(message)); }
        @Override public void error(Object message) { sink.add(String.valueOf(message)); }
        @Override public void error(Object message, Throwable t) { sink.add(String.valueOf(message)); }
        @Override public void warn(Object message) { sink.add(String.valueOf(message)); }
        @Override public void warn(Object message, Throwable t) { sink.add(String.valueOf(message)); }
        @Override public void info(Object message) { sink.add(String.valueOf(message)); }
        @Override public void info(Object message, Throwable t) { sink.add(String.valueOf(message)); }
        @Override public void debug(Object message) { sink.add(String.valueOf(message)); }
        @Override public void debug(Object message, Throwable t) { sink.add(String.valueOf(message)); }
        @Override public void trace(Object message) { sink.add(String.valueOf(message)); }
        @Override public void trace(Object message, Throwable t) { sink.add(String.valueOf(message)); }
    }
}
