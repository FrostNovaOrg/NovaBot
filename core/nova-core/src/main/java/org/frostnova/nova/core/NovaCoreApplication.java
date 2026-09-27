package org.frostnova.nova.core;

import org.frostnova.nova.core.safemode.SafeModeServer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

@EnableAsync
@EnableRetry
@EnableCaching
@EnableScheduling
@EnableAspectJAutoProxy(exposeProxy = true)
@SpringBootApplication
public class NovaCoreApplication {
    /**
     * 主配置文件路径
     */
    private static final Path CONFIG_PATH = Path.of("application.yml");

    public static void main(String[] args) {
        try {
            SpringApplication.run(NovaCoreApplication.class, args);
        } catch (Exception e) {
            if (!isConfigurationFailure(e)) {
                throw e;
            }

            // 配置有误时不能就这么退出：配置界面随主程序一同挂掉后，远程部署的使用者就被挡在门外，
            // 只能 SSH 进去手工改文件。此处退化为安全模式，至少让人能把配置改回来。
            new SafeModeServer(CONFIG_PATH, describe(e)).start();
        }
    }

    /**
     * 判断启动失败是否由配置问题导致
     * <p>
     * 仅对配置类失败启用安全模式：端口被占用、磁盘故障等问题进安全模式并无帮助，
     * 反而会掩盖真正的原因，让进程看起来「还活着」。
     * @param failure 启动异常
     * @return 是否为配置问题
     */
    private static boolean isConfigurationFailure(Throwable failure) {
        // 起因链可能成环（A 的起因是 B、B 的起因又是 A）：走过的每一层按对象身份记下，
        // 走到走过的就停，不绕着环找第二遍
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof BindException
                    || current.getClass().getName().startsWith("org.yaml.snakeyaml.")
                    || current.getClass().getName().endsWith("ConfigDataResourceNotFoundException")
                    || current.getClass().getName().endsWith("InvalidConfigDataPropertyException")) {
                return true;
            }
        }

        return false;
    }

    /**
     * 提取一条便于定位问题的失败说明
     * <p>
     * 取最内层异常：外层多为 Spring 的包装信息，真正指出「哪一行、哪个字段」的通常在最里面。
     * @param failure 启动异常
     * @return 失败说明
     */
    private static String describe(Throwable failure) {
        Throwable root = failure;
        // 起因链可能成环（A 的起因是 B、B 的起因又是 A）：走过的每一层按对象身份记下，
        // 最深一层停在环上最后一个没走过的那一层
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(root);
        while (root.getCause() != null && seen.add(root.getCause())) {
            root = root.getCause();
        }

        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
