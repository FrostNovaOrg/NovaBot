package org.frostnova.nova.core.release;

import org.frostnova.nova.core.properties.LiveProperties;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.support.SpringApplicationJsonEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 供发版等窗从服务的工作目录读取直播数据，不启动 NovaBot。 */
public final class LiveWindowProbe {
    private static final String LIVE_DATA_PATH = "novabot.core.live.live-data-path";
    private static final String LIVE_STATUS_PREFIX = "LiveStatus:";
    private static final long MAX_AGE_MILLIS = Duration.ofMinutes(10).toMillis();

    private LiveWindowProbe() {
    }

    public static void main(String[] args) {
        String result = args.length == 1 ? probe(Path.of(args[0])) : unknown("读不到正在运行的服务进程");
        System.out.println("PROCESS " + result);
    }

    private static String probe(Path mainProcDir) {
        ProcessHandle main;
        BotProcess bot;
        long startedAt;
        try {
            main = ProcessHandle.of(Long.parseLong(mainProcDir.getFileName().toString())).orElseThrow();
            bot = runningBot(main, mainProcDir);
            // Linux 上的启动时刻由 ProcessHandle 从进程的 stat 取得。
            startedAt = bot.handle().info().startInstant().orElseThrow().toEpochMilli();
            if (!Files.isSameFile(Path.of("."), bot.procDir().resolve("cwd"))) {
                return unknown("探针与正在运行的程序工作目录不一致");
            }
        } catch (IOException | RuntimeException e) {
            return unknown("读不到正在运行的程序及其启动时刻");
        }

        Path path;
        try {
            path = liveDataPath(bot);
        } catch (IOException | RuntimeException e) {
            // 解析异常可能含配置原文，探针只返回固定的原因。
            return unknown("无法从配置读取直播数据文件路径");
        }
        String result = inspect(path, startedAt);
        if (!main.isAlive() || !bot.handle().isAlive()) {
            return unknown("探测时服务进程已退出");
        }
        return result;
    }

    private record BotProcess(ProcessHandle handle, Path procDir, List<String> args, int jarOption) {
    }

    private static BotProcess runningBot(ProcessHandle main, Path procDir) throws IOException {
        BotProcess bot = readLaunch(main, procDir);
        if (bot != null) {
            return bot;
        }
        // 模板的 MainPID 是等待 Java 退出的 start.sh，取实际写盘的 JVM。
        for (ProcessHandle child : main.descendants().toList()) {
            BotProcess candidate = readLaunch(child, procDir.resolveSibling(Long.toString(child.pid())));
            if (candidate != null) {
                if (bot != null) {
                    throw new IOException("服务下有多个 NovaBot 进程");
                }
                bot = candidate;
            }
        }
        if (bot == null) {
            throw new IOException("服务下没有找到 NovaBot 进程");
        }
        return bot;
    }

    private static BotProcess readLaunch(ProcessHandle handle, Path procDir) throws IOException {
        List<String> args = procEntries(procDir.resolve("cmdline"));
        Path executable = Path.of(args.get(0)).getFileName();
        if (executable == null || !"java".equals(executable.toString())) {
            return null;
        }
        int jarOption = args.indexOf("-jar");
        if (jarOption == -1 || jarOption + 1 >= args.size()) {
            return null;
        }
        Path jar = Path.of(args.get(jarOption + 1)).getFileName();
        if (jar == null || !List.of("NovaBot.jar", "StarBotCore.jar").contains(jar.toString())) {
            return null;
        }
        return new BotProcess(handle, procDir, args, jarOption);
    }

    private static List<String> procEntries(Path path) throws IOException {
        return List.of(Files.readString(path).split("\0"));
    }

    private static Path liveDataPath(BotProcess bot) throws IOException {
        // 将运行实例的参数作为配置数据交给 Spring，避免让探针 JVM 执行服务的启动选项。
        Map<String, Object> systemProperties = new HashMap<>();
        for (String arg : bot.args().subList(1, bot.jarOption())) {
            if (arg.startsWith("-D")) {
                putProperty(systemProperties, arg.substring(2));
            }
        }
        Map<String, Object> systemEnvironment = new HashMap<>();
        for (String entry : procEntries(bot.procDir().resolve("environ"))) {
            if (!entry.isEmpty()) {
                putProperty(systemEnvironment, entry);
            }
        }
        String[] applicationArgs = bot.args().subList(bot.jarOption() + 2, bot.args().size()).toArray(String[]::new);
        var environment = new StandardEnvironment();
        var sources = environment.getPropertySources();
        String sysEnv = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;
        String sysProps = StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME;
        sources.replace(sysEnv, new SystemEnvironmentPropertySource(sysEnv, systemEnvironment));
        sources.replace(sysProps, new MapPropertySource(sysProps, systemProperties));
        sources.addFirst(new SimpleCommandLinePropertySource(applicationArgs));
        new SpringApplicationJsonEnvironmentPostProcessor().postProcessEnvironment(environment, null);
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        String value = Binder.get(environment).bind(LIVE_DATA_PATH, String.class)
                .orElseGet(() -> new LiveProperties().getLiveDataPath());
        if (value.isBlank() || value.contains("${")) {
            throw new IllegalArgumentException();
        }
        return Path.of(value);
    }

    private static void putProperty(Map<String, Object> properties, String entry) {
        int equals = entry.indexOf('=');
        if (equals == -1) {
            properties.put(entry, "");
        } else {
            properties.put(entry.substring(0, equals), entry.substring(equals + 1));
        }
    }

    private static String inspect(Path path, long startedAt) {
        try {
            if (!Files.readAttributes(path, BasicFileAttributes.class).isRegularFile()) {
                return unknown("无法读取直播数据文件");
            }
            // 快照还含有场次统计，逐项读取可跳过无关的数据。
            var mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
            try (InputStream input = Files.newInputStream(path);
                 JsonParser json = mapper.createParser(input)) {
                return inspectSnapshot(json, startedAt);
            }
        } catch (NoSuchFileException e) {
            return unknown("直播数据文件不存在");
        } catch (IOException | JacksonIOException e) {
            return unknown("无法读取直播数据文件");
        } catch (JacksonException e) {
            return unknown("直播数据文件不是有效的 JSON");
        }
    }

    private static String inspectSnapshot(JsonParser json, long startedAt) {
        if (json.nextToken() != JsonToken.START_OBJECT) {
            return unknown("直播数据文件格式不对");
        }

        long lastSaveTime = 0;
        int liveCount = 0;
        boolean hasPlatform = false;
        while (json.nextToken() == JsonToken.PROPERTY_NAME) {
            String key = json.currentName();
            JsonToken value = json.nextToken();
            if ("LastSaveTime".equals(key)) {
                if (value == JsonToken.VALUE_NUMBER_INT) {
                    lastSaveTime = json.getLongValue();
                }
            } else if (key.startsWith(LIVE_STATUS_PREFIX)) {
                if (value != JsonToken.START_OBJECT || key.length() == LIVE_STATUS_PREFIX.length()) {
                    return unknown("直播数据文件的直播状态格式不对");
                }
                hasPlatform = true;
                while (json.nextToken() == JsonToken.PROPERTY_NAME) {
                    JsonToken status = json.nextToken();
                    if (status == JsonToken.VALUE_TRUE) {
                        liveCount++;
                    } else if (status != JsonToken.VALUE_FALSE) {
                        return unknown("直播数据文件的直播状态格式不对");
                    }
                }
                if (json.currentToken() != JsonToken.END_OBJECT) {
                    return unknown("直播数据文件的直播状态格式不对");
                }
            }
            json.skipChildren();
        }
        if (json.currentToken() != JsonToken.END_OBJECT || json.nextToken() != null) {
            return unknown("直播数据文件不是有效的 JSON");
        }
        if (!hasPlatform) {
            return unknown("直播数据文件没有 LiveStatus:* 键");
        }
        long now = System.currentTimeMillis();
        if (lastSaveTime <= 0 || lastSaveTime > now) {
            return unknown("直播数据文件的 LastSaveTime 缺失或无效");
        }
        if (lastSaveTime <= startedAt) {
            return unknown("数据文件不是当前运行的程序写的（或它启动后还没写过）");
        }
        long age = now - lastSaveTime;
        if (age > MAX_AGE_MILLIS) {
            return unknown("直播数据文件至少 %d 分钟没更新".formatted(Duration.ofMillis(age).toMinutes()));
        }
        if (liveCount > 0) {
            return "LIVE %d 位主播在播，继续等".formatted(liveCount);
        }
        return "ALLIDLE 直播数据在 10 分钟内更新，全员空闲，可部署";
    }

    private static String unknown(String reason) {
        return "UNKNOWN " + reason + "，判不了在不在播，继续等";
    }
}
