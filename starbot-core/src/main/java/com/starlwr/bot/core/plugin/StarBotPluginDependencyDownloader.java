package com.starlwr.bot.core.plugin;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.util.HttpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * StarBot 插件依赖下载器
 * <p>
 * 下载的 jar 会在下次启动时被加载进 JVM，因此下载环节等同于代码执行入口，需要相应的谨慎：
 * 坐标来自插件自带的 dependency.json（第三方可控），下载内容必须校验完整性，落盘路径必须受限。
 */
@Slf4j
@Component
public class StarBotPluginDependencyDownloader {
    /**
     * 依赖下载完毕后使用的退出码
     * <p>
     * 不再自行派生子进程重启：那样父进程需一直驻留等待子进程结束，白白占着一份内存，
     * 在 systemd 下进程树也不正确。改为以非零码退出，交给 systemd 的 Restart 策略
     * 或 start.sh 的重启循环拉起。
     */
    public static final int RESTART_EXIT_CODE = 90;

    /**
     * 合法的 Maven 坐标片段
     * <p>
     * 坐标取自插件自带的 dependency.json，属于第三方可控输入。它既用于拼接下载地址，
     * 又用于拼接落盘文件名，不加限制的话，形如 {@code ../} 的取值即可写出目录之外。
     */
    private static final Pattern COORDINATE = Pattern.compile("^[A-Za-z0-9._-]+$");

    /**
     * 依赖存放目录
     */
    private static final Path PLUGINS_LIB = Paths.get("plugins-lib");

    private final ApplicationContext context;

    private final ApplicationArguments arguments;

    private final StarBotPluginLoader loader;

    private final StarBotCoreProperties properties;

    private final HttpUtil http;

    @Autowired
    public StarBotPluginDependencyDownloader(ApplicationContext context, ApplicationArguments arguments, StarBotPluginLoader loader, StarBotCoreProperties properties, HttpUtil http) {
        this.context = context;
        this.arguments = arguments;
        this.loader = loader;
        this.properties = properties;
        this.http = http;
    }

    /**
     * 下载缺失依赖
     */
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReadyEvent() {
        Set<Dependency> dependencies = loader.getNeedDownloadDependencies().values().stream().flatMap(Collection::stream).collect(Collectors.toSet());
        if (dependencies.isEmpty()) {
            return;
        }

        log.info("检测到存在 {} 个缺少的插件依赖: {}", dependencies.size(), describe(dependencies));

        if (arguments.containsOption("skip-download-dependency")) {
            log.warn("插件依赖不完整, 已跳过依赖自动下载(--skip-download-dependency), 将有部分插件功能受限, 建议手动补全依赖后运行");
            return;
        }

        if (!properties.getPlugin().isAutoDownloadDependency()) {
            log.warn("插件依赖不完整, 已配置跳过依赖自动下载, 将有部分插件功能受限, 建议开启自动下载依赖或手动补全依赖后运行");
            return;
        }

        log.info("开始下载依赖包");

        List<Dependency> failed = downloadJars(dependencies);

        if (failed.isEmpty()) {
            log.info("依赖下载成功");
        } else {
            log.warn("依赖下载完毕, 存在未下载成功的依赖: {}", describe(failed));
            log.warn("重启后请以 --skip-download-dependency 运行, 或手动将上述依赖放入 {} 目录", PLUGINS_LIB);
        }

        log.info("依赖已就绪, 需要重启才能加载。进程将以退出码 {} 退出, 由 systemd 或启动脚本自动拉起", RESTART_EXIT_CODE);

        SpringApplication.exit(context);
        System.exit(RESTART_EXIT_CODE);
    }

    /**
     * 批量下载依赖
     * @param dependencies 依赖集合
     * @return 下载失败的依赖列表
     */
    private List<Dependency> downloadJars(Set<Dependency> dependencies) {
        List<Dependency> failed = new CopyOnWriteArrayList<>();

        try {
            Files.createDirectories(PLUGINS_LIB);
        } catch (IOException e) {
            log.error("创建依赖文件夹失败", e);
            return List.copyOf(dependencies);
        }

        List<CompletableFuture<Void>> tasks = dependencies.stream()
                .map(dependency -> downloadJar(dependency, failed))
                .toList();
        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();

        return failed;
    }

    /**
     * 下载单个依赖
     */
    private CompletableFuture<Void> downloadJar(Dependency dependency, List<Dependency> failed) {
        if (!isValidCoordinate(dependency)) {
            log.error("依赖坐标 {} 含非法字符, 已拒绝下载", dependency.getId());
            failed.add(dependency);
            return CompletableFuture.completedFuture(null);
        }

        Path target;
        try {
            target = resolveTarget(dependency);
        } catch (IOException e) {
            log.error("依赖 {} 的落盘路径非法, 已拒绝下载: {}", dependency.getId(), e.getMessage());
            failed.add(dependency);
            return CompletableFuture.completedFuture(null);
        }

        List<String> urls = properties.getPlugin().getMavenBaseUrls().stream()
                .map(baseUrl -> baseUrl + "/" + relativePath(dependency))
                .toList();

        return attemptDownload(urls, 0, target, dependency, failed);
    }

    /**
     * 逐个仓库尝试下载
     */
    private CompletableFuture<Void> attemptDownload(List<String> urls, int index, Path target, Dependency dependency, List<Dependency> failed) {
        if (index >= urls.size()) {
            log.error("无法下载依赖 {}", target.getFileName());
            failed.add(dependency);
            return CompletableFuture.completedFuture(null);
        }

        String url = urls.get(index);
        log.info("开始从 {} 下载依赖至 {}", url, target);

        return http.asyncGetBytes(url)
                .handle((bytes, error) -> {
                    if (error != null || bytes == null) {
                        log.warn("从 {} 下载依赖失败", url);
                        return null;
                    }
                    return bytes;
                })
                .thenCompose(bytes -> {
                    if (bytes == null) {
                        return attemptDownload(urls, index + 1, target, dependency, failed);
                    }

                    return verify(url, bytes).thenCompose(verified -> {
                        if (!verified) {
                            // 校验不过一律换源重试：内容对不上就等于来源不可信，宁可不装也不能装错的
                            return attemptDownload(urls, index + 1, target, dependency, failed);
                        }

                        try {
                            writeAtomically(target, bytes);
                            log.info("依赖 {} 下载完毕并校验通过", target.getFileName());
                            return CompletableFuture.completedFuture(null);
                        } catch (IOException e) {
                            log.error("写入依赖文件 {} 失败", target, e);
                            return attemptDownload(urls, index + 1, target, dependency, failed);
                        }
                    });
                });
    }

    /**
     * 比对 Maven 仓库提供的 SHA-1 校验和
     * <p>
     * 下载的 jar 下次启动就会被加载进 JVM，不校验完整性意味着镜像被投毒或中间人替换时可直接执行任意代码。
     * 仓库拿不到校验和时保守放行并告警——若因此拒绝安装，只会逼使用者关掉自动下载，反而更糟。
     * @return 是否可以落盘
     */
    private CompletableFuture<Boolean> verify(String jarUrl, byte[] bytes) {
        return http.asyncGetBytes(jarUrl + ".sha1")
                .handle((raw, error) -> {
                    if (error != null || raw == null || raw.length == 0) {
                        log.warn("未能获取 {} 的 SHA-1 校验和, 已跳过完整性校验", jarUrl);
                        return true;
                    }

                    // 校验和文件通常形如 "<sha1>  <文件名>"，只取首段
                    String expected = new String(raw, StandardCharsets.UTF_8).trim().split("\\s+")[0].toLowerCase();
                    String actual = sha1(bytes);

                    if (!expected.equals(actual)) {
                        log.error("依赖 {} 的 SHA-1 校验不通过, 期望 {}, 实际 {}, 已拒绝使用", jarUrl, expected, actual);
                        return false;
                    }

                    return true;
                });
    }

    private String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (Exception e) {
            // JDK 必然支持 SHA-1，走到这里说明运行环境异常，按校验失败处理
            log.error("计算 SHA-1 失败", e);
            return "";
        }
    }

    /**
     * 原子写入
     * <p>
     * 直接写目标路径的话，中途失败会留下一个长度不足的 jar，下次启动加载时才会暴露成
     * 难以理解的类加载错误。
     */
    private void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path temp = Files.createTempFile(PLUGINS_LIB, target.getFileName().toString(), ".part");
        try {
            Files.write(temp, bytes);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    /**
     * 校验坐标是否只含合法字符
     */
    boolean isValidCoordinate(Dependency dependency) {
        return isValidSegment(dependency.getGroupId())
                && isValidSegment(dependency.getArtifactId())
                && isValidSegment(dependency.getVersion());
    }

    private boolean isValidSegment(String value) {
        return value != null && !value.isBlank() && COORDINATE.matcher(value).matches();
    }

    /**
     * 计算依赖在 Maven 仓库中的相对路径
     */
    String relativePath(Dependency dependency) {
        return dependency.getGroupId().replace('.', '/')
                + "/" + dependency.getArtifactId()
                + "/" + dependency.getVersion()
                + "/" + fileName(dependency);
    }

    private String fileName(Dependency dependency) {
        return dependency.getArtifactId() + "-" + dependency.getVersion() + ".jar";
    }

    /**
     * 计算落盘路径，并确认其确实位于依赖目录之内
     * <p>
     * 坐标已做字符白名单校验，此处是第二道防线：白名单万一被放宽，这里仍能拦住越权写入。
     */
    Path resolveTarget(Dependency dependency) throws IOException {
        Path directory = PLUGINS_LIB.toAbsolutePath().normalize();
        Path target = directory.resolve(fileName(dependency)).normalize();

        if (!target.getParent().equals(directory)) {
            throw new IOException("路径越出依赖目录: " + target);
        }

        return target;
    }

    private String describe(Collection<Dependency> dependencies) {
        return dependencies.stream()
                .map(dependency -> dependency.getArtifactId() + "-" + dependency.getVersion())
                .collect(Collectors.joining(", "));
    }
}
