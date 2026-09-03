package com.starlwr.bot.core.config;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 运行时数据落点守卫
 * <p>
 * 运行时数据（{@code data.json}、{@code state.json}、{@code sessions.jsonl}、
 * {@code snapshots.jsonl}、{@code timeline/}）的路径由 {@code starbot.core.live.live-data-path} 一个配置项派生，
 * 默认值 {@code data.json} 是<b>相对当前工作目录</b>的。
 * <p>
 * 生产的两种部署方式都依赖这一点，<b>不能改</b>：
 * <ul>
 *   <li>systemd：{@code WorkingDirectory=/opt/starbot}，且单元文件里有 {@code ProtectHome=true}
 *       与 {@code ReadWritePaths=/opt/starbot}——服务账号<b>根本写不进 $HOME</b>；</li>
 *   <li>容器：{@code WORKDIR /app} ＋ {@code VOLUME /app}——数据靠当前工作目录落在挂载卷里，
 *       落到卷外的话容器一重建就没了。</li>
 * </ul>
 * 出问题的只有另一种情形：<b>从仓库根把程序跑起来</b>。那时当前工作目录是仓库工作树，
 * 于是这四个文件落进仓库目录树——它们含他人 uid、昵称、群号，
 * 而 {@code .gitignore} 挡住的只是 {@code git add} 这条路，挡不住复制粘贴。
 * <p>
 * 故本守卫<b>不改默认路径</b>，只在启动时判一次「解析出来的数据目录是不是落在一棵 git 工作树里」，
 * 并按「这个落点是谁选的」分两支处置：
 * <ul>
 *   <li><b>默认派生</b>的路径落在工作树内 → 改写到 {@code ~/.novabot/} 并在日志里大声说明。
 *       没有人选过这个落点，它是当前工作目录的意外；</li>
 *   <li><b>显式配置</b>的路径落在工作树内 → <b>拒绝启动</b>。静默改写别人写下的显式选择，
 *       是替人做主；失败要大声。</li>
 * </ul>
 * 逃生阀见 {@link #ALLOW_ENV}：必须同时给出<b>非空理由</b>，理由为空按拒绝处理——
 * 一个不用说明理由就能按的开关，按过之后没人知道当时为什么按。
 * <p>
 * 🔴 本类是 {@code EnvironmentPostProcessor} 而不是普通的 Bean：
 * {@code EventStreamTokenService} 在<b>构造时</b>就读了这个路径，任何 {@code @PostConstruct}
 * 形态的守卫都可能排在它后面。守卫排在被守的东西后面，等于没有守卫。
 */
public class DataLocationGuard implements EnvironmentPostProcessor {
    /**
     * 数据文件路径配置项。其余运行时文件与目录都由它派生：取它的父目录。
     */
    static final String KEY = "starbot.core.live.live-data-path";

    /**
     * 与 {@link LiveProperties#getLiveDataPath()} 的默认值一致。
     * <p>
     * 🔴 两处必须同时改。这里读不到那个默认值——本类跑在任何 Bean 存在之前。
     */
    static final String DEFAULT_PATH = "data.json";

    /**
     * 逃生阀开关与理由、开发落点：与日志落点<b>共用同一份</b>（{@link WorktreeGuard}）。
     * <p>
     * 🔴 同一件事不该有两个开关——两个开关意味着有人关了一个以为都关了。
     */
    static final String ALLOW_ENV = WorktreeGuard.ALLOW_ENV;

    static final String REASON_ENV = WorktreeGuard.REASON_ENV;

    static final String DEV_DIR = WorktreeGuard.DEV_DIR;

    private static final String SOURCE_NAME = "novaBotDataLocationGuard";

    private final Log log;

    public DataLocationGuard(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(DataLocationGuard.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String configured = environment.getProperty(KEY);
        boolean explicit = configured != null && !configured.isBlank();

        // 相对路径按当前工作目录解析——绝对路径 resolve 原样返回，两种情形同一行照顾到。
        Path effective = cwd().resolve(explicit ? configured : DEFAULT_PATH).toAbsolutePath().normalize();
        Path dir = effective.getParent();
        if (dir == null) {
            return;
        }

        Path worktree = WorktreeGuard.findWorktreeRoot(dir);
        if (worktree == null) {
            // 生产形状：目录上溯不到 .git。一字不改，落原地。
            return;
        }

        if (allowed()) {
            log.warn("运行时数据落在 git 工作树内（" + dir + "，工作树 " + worktree + "），"
                    + "已按 " + ALLOW_ENV + " 放行。理由：" + reason());
            log.warn("🔴 该目录下会生成 data.json / state.json / sessions.jsonl / snapshots.jsonl / timeline/，"
                    + "都含他人 uid、昵称与群号。.gitignore 挡得住 git add，挡不住复制粘贴。");
            return;
        }

        if (explicit) {
            throw new IllegalStateException(String.join("\n",
                    "",
                    "🔴 拒绝启动：配置项 " + KEY + " 指向的目录落在 git 工作树内。",
                    "",
                    "    配置值    ：" + configured,
                    "    解析后目录：" + dir,
                    "    工作树根  ：" + worktree,
                    "",
                    "该目录下会生成 data.json / state.json / sessions.jsonl / snapshots.jsonl / timeline/，",
                    "都含他人 uid、昵称与群号；.gitignore 挡得住 git add，挡不住复制粘贴。",
                    "",
                    "这是你显式写下的路径，所以程序不替你改它，只停下来告诉你。三条出路：",
                    "  1. 把 " + KEY + " 改到仓库工作树以外；",
                    "  2. 换一个不在工作树内的工作目录启动；",
                    "  3. 确有必要就按逃生阀，且必须写明理由：",
                    "       " + ALLOW_ENV + "=1 " + REASON_ENV + "='<为什么>'",
                    "     理由为空同样拒绝——按过而没人知道为什么按的开关，下一个人无从判断能不能拔掉。",
                    ""));
        }

        Path dev = Path.of(home(), DEV_DIR).toAbsolutePath().normalize();
        Path rewritten = dev.resolve(Path.of(DEFAULT_PATH).getFileName());

        log.warn("🔴 运行时数据的默认落点在 git 工作树内（" + dir + "，工作树 " + worktree + "），已改写。");
        log.warn("    改写前：" + effective);
        log.warn("    改写后：" + rewritten);
        log.warn("    原因：默认路径 " + DEFAULT_PATH + " 相对当前工作目录解析，而当前工作目录是仓库工作树。"
                + "这个落点不是任何人选的，是当前工作目录的意外，故改写而不是停机。");
        log.warn("    要落回原地请显式配置 " + KEY + "（那时若仍在工作树内，程序会拒绝启动而不是改写）。");

        Map<String, Object> overrides = new HashMap<>();
        overrides.put(KEY, rewritten.toString());
        environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, overrides));
    }

    private boolean allowed() {
        return WorktreeGuard.allowed(this::env);
    }

    private String reason() {
        return WorktreeGuard.reason(this::env);
    }

    /**
     * 环境变量读取。<b>留给测试覆写</b>——进程内改不了 {@code System.getenv}，
     * 而这四个分支不能只靠人跑一遍确认。
     * <p>
     * 🔴 这个缝隙只证得了<b>判断逻辑</b>：真正「日志里看得见改写、程序确实从新路径读」
     * 那一层，要靠把程序真起一次来证，单元测试顶不了那个位置。
     */
    String env(String name) {
        return System.getenv(name);
    }

    /**
     * 用户主目录。同上，留给测试覆写。
     */
    String home() {
        return System.getProperty("user.home");
    }

    /**
     * 当前工作目录。同上，留给测试覆写。
     * <p>
     * 🔴 不能靠在测试里改 {@code user.dir} 来控制它：默认文件系统在<b>构造时</b>就把
     * 当时的 {@code user.dir} 记下了，之后再改那个属性，{@code toAbsolutePath()} 也不会跟着变。
     * 用它写出来的测试会「过」，而过的原因与被测的分支无关。
     */
    Path cwd() {
        return Path.of("").toAbsolutePath();
    }
}
