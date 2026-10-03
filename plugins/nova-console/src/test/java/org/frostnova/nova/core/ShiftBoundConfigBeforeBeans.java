package org.frostnova.nova.core;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 只在测试进程里、并且显式打开时，于绑定之后、任何对象构造之前用旁边那份换上配置文件。
 * <p>
 * 用来造「绑定那一刻」和「对象构造那一刻」之间文件变过的那一例。
 * 没打开时什么都不做。这个类不进产品，靠测试启动时单独放上的登记才被找到。
 */
public class ShiftBoundConfigBeforeBeans implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    /**
     * 打开后，若旁边有 {@code application.yml.next}，在构造对象之前换上它
     */
    public static final String PROPERTY = "novabot.standby.shift-before-beans";

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        if (!Boolean.getBoolean(PROPERTY)) {
            return;
        }
        context.addBeanFactoryPostProcessor(factory -> shift());
    }

    private static void shift() {
        Path next = Path.of("application.yml.next");
        if (!Files.isRegularFile(next)) {
            return;
        }
        try {
            Files.move(next, Path.of("application.yml"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
