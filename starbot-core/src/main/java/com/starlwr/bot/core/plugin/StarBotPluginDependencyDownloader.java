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
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * StarBot 插件依赖下载器
 */
@Slf4j
@Component
public class StarBotPluginDependencyDownloader {
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

        log.info("检测到存在 {} 个缺少的插件依赖: {}", dependencies.size(), dependencies.stream().map(dependency -> dependency.getArtifactId() + "-" + dependency.getVersion()).collect(Collectors.joining(", ")));

        if (arguments.containsOption("skip-download-dependency")) {
            log.warn("插件依赖不完整, 已跳过依赖自动下载(--skip-download-dependency), 将有部分插件功能受限, 建议手动补全依赖后运行");
            return;
        }

        if (!properties.getPlugin().isAutoDownloadDependency()) {
            log.warn("插件依赖不完整, 已配置跳过依赖自动下载, 将有部分插件功能受限, 建议开启自动下载依赖或手动补全依赖后运行");
            return;
        }

        log.info("开始下载依赖包");

        List<Dependency> failedDependencies = downloadJars(dependencies);

        if (failedDependencies.isEmpty()) {
            log.info("依赖下载成功");
        } else {
            log.warn("依赖下载完毕, 存在未下载成功的依赖: {}", failedDependencies.stream().map(dependency -> dependency.getArtifactId() + "-" + dependency.getVersion()).collect(Collectors.joining(", ")));
        }

        log.info("即将在 3 秒后重新启动 StarBot");

        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            SpringApplication.exit(context);
            System.exit(0);
        }

        RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();
        List<String> inputArguments = runtimeMxBean.getInputArguments();

        StringJoiner sj = new StringJoiner(" ");
        for (String name : arguments.getOptionNames()) {
            List<String> values = arguments.getOptionValues(name);
            if (values == null || values.isEmpty()) {
                sj.add("--" + name);
            } else {
                for (String value : values) {
                    sj.add("--" + name + "=" + value);
                }
            }
        }
        for (String nonOptionArg : arguments.getNonOptionArgs()) {
            sj.add(nonOptionArg);
        }

        String cmdLine = "java " + String.join(" ", inputArguments) + " -jar " + runtimeMxBean.getClassPath() + " " + sj;

        if (!failedDependencies.isEmpty()) {
            cmdLine += " --skip-download-dependency";
        }

        try {
            SpringApplication.exit(context);

            log.info(cmdLine);
            ProcessBuilder builder = new ProcessBuilder(cmdLine.split(" "));
            builder.inheritIO();
            Process process = builder.start();

            int code = process.waitFor();
            System.exit(code);
        } catch (IOException e) {
            log.error("重新启动 StarBot 失败", e);
            System.exit(0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.exit(0);
        }
    }

    /**
     * 获取依赖下载路径
     * @param dependency 依赖
     * @return 下载路径
     */
    private String getJarPath(Dependency dependency) {
        String groupPath = dependency.getGroupId().replace('.', '/');
        String version = dependency.getVersion();
        String artifactId = dependency.getArtifactId();
        String fileName = artifactId + "-" + version + ".jar";
        return groupPath + "/" + artifactId + "/" + version + "/" + fileName;
    }

    /**
     * 批量下载依赖
     * @param dependencies 依赖集合
     * @return 下载失败的依赖列表
     */
    private List<Dependency> downloadJars(Set<Dependency> dependencies) {
        List<Dependency> failedDependencies = new CopyOnWriteArrayList<>();

        Path pluginsLibDir = Paths.get("plugins-lib");

        try {
            if (!Files.exists(pluginsLibDir)) {
                Files.createDirectories(pluginsLibDir);
            }
        } catch (IOException e) {
            log.error("创建依赖文件夹失败", e);
        }

        List<CompletableFuture<Void>> tasks = dependencies.stream().map(dependency -> downloadJar(pluginsLibDir, dependency, failedDependencies)).toList();
        CompletableFuture<Void> allDone = CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]));
        allDone.join();

        return failedDependencies;
    }

    /**
     * 下载依赖
     * @param pluginsLibDir 依赖目录
     * @param dependency 依赖
     * @param failedDependencies 输出参数，下载失败的依赖列表
     */
    private CompletableFuture<Void> downloadJar(Path pluginsLibDir, Dependency dependency, List<Dependency> failedDependencies) {
        String jarPath = getJarPath(dependency);
        String fileName = dependency.getArtifactId() + "-" + dependency.getVersion() + ".jar";
        Path targetFile = pluginsLibDir.resolve(fileName);

        List<String> urls = properties.getPlugin().getMavenBaseUrls().stream().map(baseUrl -> baseUrl + "/" + jarPath).collect(Collectors.toList());
        return attemptDownload(urls, 0, targetFile, dependency, failedDependencies);
    }

    /**
     * 递归尝试下载依赖
     * @param urls 下载地址列表
     * @param index 当前尝试的索引
     * @param targetFile 目标文件路径
     * @param dependency 依赖
     * @param failedDependencies 输出参数，下载失败的依赖列表
     */
    private CompletableFuture<Void> attemptDownload(List<String> urls, int index, Path targetFile, Dependency dependency, List<Dependency> failedDependencies) {
        if (index >= urls.size()) {
            log.error("无法下载依赖 {}", targetFile.getFileName());
            failedDependencies.add(dependency);
            return CompletableFuture.completedFuture(null);
        }

        String url = urls.get(index);
        log.info("开始从 {} 下载依赖至 {}", url, targetFile);
        return http.asyncGetBytes(url).handle((result, ex) -> {
            if (ex != null) {
                log.warn("从 {} 下载依赖失败", url, ex);
                return null;
            }
            if (result == null) {
                log.warn("从 {} 下载依赖失败", url);
                return null;
            }
            return result;
        }).thenCompose(result -> {
            if (result != null) {
                try {
                    Files.write(targetFile, result);
                    log.info("依赖 {} 下载完毕", targetFile.getFileName());
                    return CompletableFuture.completedFuture(null);
                } catch (IOException e) {
                    log.error("写入依赖文件 {} 失败", targetFile, e);
                    return attemptDownload(urls, index + 1, targetFile, dependency, failedDependencies);
                }
            } else {
                return attemptDownload(urls, index + 1, targetFile, dependency, failedDependencies);
            }
        });
    }
}
