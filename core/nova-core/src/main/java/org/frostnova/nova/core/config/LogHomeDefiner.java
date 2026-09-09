package org.frostnova.nova.core.config;

import ch.qos.logback.core.spi.ContextAwareBase;
import ch.qos.logback.core.spi.PropertyDefiner;

import java.nio.file.Path;
import java.util.function.Function;

/**
 * 日志落点守卫（logback 的 {@code LOG_HOME}）
 * <p>
 * 三棵日志树（{@code logs/}、{@code EventDebug/}、{@code NetworkDebug/}）在
 * {@code logback.xml} 里写的都是<b>相对路径</b>，于是它们和运行时数据是同一个洞的两半：
 * 从仓库工作树里跑起来，日志就落进仓库目录树。而日志比那四个数据文件<b>更容易触发</b>——
 * 跑一次 {@code mvn test} 就有，不用启动程序；实测三个模块下各写一份。
 * 日志里有观众 uid、昵称与房间号。
 * <p>
 * 🔴 故本类做成 logback 的 {@link PropertyDefiner} 而<b>不是</b> Spring 的组件：
 * {@code mvn test} 跑单元测试时 Spring 可能根本没起来，logback 却已经在写盘了。
 * <b>挂在 Spring 上的守卫盖不住那条路。</b>
 * <p>
 * 处置与 {@link DataLocationGuard} 同款两分支：
 * <ul>
 *   <li><b>没人指定</b>（默认落当前工作目录）而当前工作目录在工作树内 → 改写到 {@code ~/.novabot/}；</li>
 *   <li><b>显式指定</b>了 {@code LOG_HOME}（系统属性或环境变量）而它在工作树内 → <b>拒绝</b>，
 *       不替人改主意；</li>
 *   <li>上溯不到 {@code .git}（生产形状）→ 返回 {@code "."}，<b>与改动前逐字节同样的落点</b>。</li>
 * </ul>
 * 逃生阀与数据落点<b>共用同一对</b>（{@link WorktreeGuard#ALLOW_ENV}）——同一件事不该有两个开关，
 * 两个开关意味着有人关了一个以为都关了。
 */
public class LogHomeDefiner extends ContextAwareBase implements PropertyDefiner {
    /**
     * 显式指定日志根的键。系统属性与环境变量同名，前者优先。
     */
    static final String KEY = "LOG_HOME";

    @Override
    public String getPropertyValue() {
        try {
            return resolve();
        } catch (IllegalStateException e) {
            // logback 对 definer 抛出的异常处置未必显眼，故再往 stderr 说一次。
            // 🔴 拒绝这一支的全部价值就在「大声」，被吞掉就等于静默改写。
            System.err.println(e.getMessage());
            throw e;
        }
    }

    String resolve() {
        String configured = property(KEY);
        boolean explicit = configured != null && !configured.isBlank();

        Path base = cwd().resolve(explicit ? configured : ".").toAbsolutePath().normalize();
        Path worktree = WorktreeGuard.findWorktreeRoot(base);
        if (worktree == null) {
            // 生产形状：一字不改。相对 "." 即维持改动前的落点。
            return explicit ? configured : ".";
        }

        Function<String, String> env = this::env;
        if (WorktreeGuard.allowed(env)) {
            System.err.println("[NovaBot] 日志落在 git 工作树内（" + base + "），已按 "
                    + WorktreeGuard.ALLOW_ENV + " 放行。理由：" + WorktreeGuard.reason(env));
            return explicit ? configured : ".";
        }

        if (explicit) {
            throw new IllegalStateException(String.join("\n",
                    "",
                    "🔴 拒绝配置日志：" + KEY + " 指向的目录落在 git 工作树内。",
                    "",
                    "    " + KEY + "   ：" + configured,
                    "    解析后    ：" + base,
                    "    工作树根  ：" + worktree,
                    "",
                    "日志里有观众 uid、昵称与房间号；.gitignore 挡得住 git add，挡不住复制粘贴。",
                    "这是你显式写下的路径，所以程序不替你改它。改它，或按逃生阀并写明理由：",
                    "    " + WorktreeGuard.ALLOW_ENV + "=1 " + WorktreeGuard.REASON_ENV + "='<为什么>'",
                    ""));
        }

        Path dev = Path.of(home(), WorktreeGuard.DEV_DIR).toAbsolutePath().normalize();
        System.err.println("[NovaBot] 日志的默认落点在 git 工作树内（" + base + "，工作树 " + worktree
                + "），已改写到 " + dev + "。日志含观众 uid 与昵称，不该落在仓库目录树里。");
        return dev.toString();
    }

    /**
     * 系统属性优先于环境变量。留给测试覆写。
     */
    String property(String name) {
        String v = System.getProperty(name);
        return v != null ? v : System.getenv(name);
    }

    /**
     * 环境变量读取。留给测试覆写。
     */
    String env(String name) {
        return System.getenv(name);
    }

    /**
     * 用户主目录。留给测试覆写。
     */
    String home() {
        return System.getProperty("user.home");
    }

    /**
     * 当前工作目录。留给测试覆写。
     * <p>
     * 🔴 不能靠在测试里改 {@code user.dir}：默认文件系统在构造时就把当时的值记下了。
     */
    Path cwd() {
        return Path.of("").toAbsolutePath();
    }
}
