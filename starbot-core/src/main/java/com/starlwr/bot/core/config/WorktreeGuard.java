package com.starlwr.bot.core.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * 「这个落点是不是在 git 工作树里」的共用判断
 * <p>
 * 运行时数据（{@link DataLocationGuard}）与运行日志（{@link LogHomeDefiner}）两处都要问同一句话。
 * 🔴 <b>两把尺量同一件事必生漂移</b>，故判断只写一份，两边共用；差别只在各自怎么处置。
 */
final class WorktreeGuard {
    /**
     * 逃生阀开关。取 {@code 1} 时放行「落在 git 工作树内」。
     */
    static final String ALLOW_ENV = "NOVABOT_ALLOW_DATA_IN_WORKTREE";

    /**
     * 逃生阀理由。开关按下时<b>必须非空</b>，否则按拒绝处理——
     * 一个不用说明理由就能按的开关，按过之后没人知道当时为什么按。
     */
    static final String REASON_ENV = "NOVABOT_ALLOW_DATA_IN_WORKTREE_REASON";

    /**
     * 落在工作树内时，默认派生的落点改写到这里（相对用户主目录）。
     */
    static final String DEV_DIR = ".novabot";

    private WorktreeGuard() {
    }

    /**
     * 从给定目录逐级上溯，找出它所属的 git 工作树根。
     * <p>
     * {@code .git} 既可能是目录（普通克隆），也可能是文件（{@code git worktree} 检出的树、
     * 子模块）——<b>两种都算</b>。只认目录的话，{@code git worktree} 出来的树会被读成
     * 「不在工作树内」，而构建时用的正是那种形态。
     *
     * @return 工作树根；不在任何工作树内时返回 {@code null}
     */
    static Path findWorktreeRoot(Path dir) {
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve(".git"))) {
                return p;
            }
        }
        return null;
    }

    /**
     * 逃生阀是否按下<b>且</b>给了非空理由。
     *
     * @param env 环境变量读取器（留给测试覆写——进程内改不了 {@code System.getenv}）
     */
    static boolean allowed(Function<String, String> env) {
        return "1".equals(trimmed(env.apply(ALLOW_ENV))) && !reason(env).isEmpty();
    }

    /**
     * 逃生阀理由，已去空白；未给时为空串。
     */
    static String reason(Function<String, String> env) {
        return trimmed(env.apply(REASON_ENV));
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
