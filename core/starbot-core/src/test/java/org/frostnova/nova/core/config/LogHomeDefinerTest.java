package org.frostnova.nova.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日志落点守卫测试
 * <p>
 * 与运行时数据那把守卫同款四条。差别只在<b>阴性那条更要紧</b>：
 * 生产形状必须返回 {@code "."}，也就是与本改动之前<b>逐字节相同</b>的落点——
 * 🔴 这一格量的是「这次改动没有动生产」，而那正是最容易被当成不必测的一句话。
 */
@DisplayName("日志落点守卫")
class LogHomeDefinerTest {
    @TempDir
    Path tmp;

    private LogHomeDefiner definer(Path cwd, Path home, Map<String, String> vars) {
        return new LogHomeDefiner() {
            @Override
            String property(String name) {
                return vars.get(name);
            }

            @Override
            String env(String name) {
                return vars.get(name);
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

    private Path worktree(String name) throws IOException {
        Path root = Files.createDirectories(tmp.resolve(name));
        Files.createDirectories(root.resolve(".git"));
        return root;
    }

    @Test
    @DisplayName("① 没人指定 ＋ 当前工作目录在工作树内 —— 改写到开发落点")
    void defaultInsideWorktreeIsRewritten() throws IOException {
        Path tree = worktree("repo");
        Path home = Files.createDirectories(tmp.resolve("home"));

        String v = definer(tree, home, Map.of()).resolve();

        assertEquals(home.resolve(WorktreeGuard.DEV_DIR).toString(), v,
                "日志的默认落点在工作树内时必须被改写——跑一次 mvn test 就会写进去");
    }

    @Test
    @DisplayName("② 显式指定 LOG_HOME 且落在工作树内 —— 拒绝，不替人改主意")
    void explicitInsideWorktreeIsRefused() throws IOException {
        Path tree = worktree("repo");
        Path home = Files.createDirectories(tmp.resolve("home"));
        Map<String, String> vars = Map.of(LogHomeDefiner.KEY, tree.resolve("var").toString());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> definer(tree, home, vars).resolve());

        assertTrue(e.getMessage().contains("拒绝配置日志"), "拒绝的理由要写在异常里");
        assertTrue(e.getMessage().contains(WorktreeGuard.ALLOW_ENV), "要把逃生阀怎么按一并告诉人");
    }

    @Test
    @DisplayName("③ 逃生阀带非空理由 —— 放行，落原地")
    void escapeHatchWithReasonPasses() throws IOException {
        Path tree = worktree("repo");
        Path home = Files.createDirectories(tmp.resolve("home"));
        Map<String, String> vars = Map.of(
                WorktreeGuard.ALLOW_ENV, "1",
                WorktreeGuard.REASON_ENV, "排障要看仓内的滚动日志");

        assertEquals(".", definer(tree, home, vars).resolve(), "放行即落原地，不改写");
    }

    @Test
    @DisplayName("③ 之二 —— 开关按下但理由为空，按拒绝处理（显式那支）")
    void escapeHatchWithoutReasonStillRefused() throws IOException {
        Path tree = worktree("repo");
        Path home = Files.createDirectories(tmp.resolve("home"));
        Map<String, String> vars = new HashMap<>();
        vars.put(LogHomeDefiner.KEY, tree.resolve("var").toString());
        vars.put(WorktreeGuard.ALLOW_ENV, "1");
        vars.put(WorktreeGuard.REASON_ENV, "   ");

        assertThrows(IllegalStateException.class, () -> definer(tree, home, vars).resolve());
    }

    @Test
    @DisplayName("③ 之三 —— 理由为空时，默认那支也不许被放行成「落原地」")
    void blankReasonStillRewritesDefaultBranch() throws IOException {
        Path tree = worktree("repo");
        Path home = Files.createDirectories(tmp.resolve("home"));
        Map<String, String> vars = new HashMap<>();
        vars.put(WorktreeGuard.ALLOW_ENV, "1");

        assertEquals(home.resolve(WorktreeGuard.DEV_DIR).toString(),
                definer(tree, home, vars).resolve(),
                "空理由的开关等于没按——不能因为「按过开关」就把默认那支也放回原地");
    }

    @Test
    @DisplayName("④ 阴性 —— 生产形状返回 \".\"，与本改动之前逐字节相同的落点")
    void productionShapeReturnsDot() throws IOException {
        Path production = Files.createDirectories(tmp.resolve("opt").resolve("starbot"));
        Path home = Files.createDirectories(tmp.resolve("home"));

        assertEquals(".", definer(production, home, Map.of()).resolve(),
                "上溯不到 .git 就一字不改——logs/ 仍相对当前工作目录，systemd 与容器两条路径不变");

        Map<String, String> vars = Map.of(LogHomeDefiner.KEY, "/var/log/novabot");
        assertEquals("/var/log/novabot", definer(production, home, vars).resolve(),
                "生产形状下显式指定的值也原样返回");
    }

    @Test
    @DisplayName("⑤ 两把守卫共用同一对逃生阀 —— 同一件事不该有两个开关")
    void guardsShareOneEscapeHatch() {
        assertEquals(DataLocationGuard.ALLOW_ENV, WorktreeGuard.ALLOW_ENV);
        assertEquals(DataLocationGuard.REASON_ENV, WorktreeGuard.REASON_ENV);
        assertEquals(DataLocationGuard.DEV_DIR, WorktreeGuard.DEV_DIR);
    }
}
