package org.frostnova.nova.core;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态件写不进时不该让程序起不来：路径写坏、空值、目标是目录这几种都要接住，
 * 只打一行警告、不落临时件。
 * <p>
 * 同 JVM 直调 {@link StateFile}，不起进程、不绑端口；设过的系统属性用完还原。
 */
@DisplayName("状态件写入的几条边")
class StateFileTest {

    /**
     * 抓：路径给坏了（系统属性里有非法字符）会让程序起不来。
     */
    @Test
    @DisplayName("路径带非法字符：不抛、只打一行警告，不带栈")
    void illegalPathWarnsOnceWithoutThrowing(@TempDir Path dir) {
        String previous = System.getProperty(StateFile.PATH_PROPERTY);
        Logger logger = (Logger) LoggerFactory.getLogger(StateFile.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        String raw = dir.resolve("state.txt").toString() + (char) 0 + "bad";
        try {
            System.setProperty(StateFile.PATH_PROPERTY, raw);
            assertDoesNotThrow(() -> StateFile.write("ready"), "路径写坏不该让程序起不来");
            assertEquals(1, appender.list.size(), "只该打一行日志");
            ILoggingEvent event = appender.list.get(0);
            assertEquals(Level.WARN, event.getLevel(), "这一行应是 WARN");
            assertTrue(event.getFormattedMessage().contains("InvalidPathException"), "警告里要带上原因类名");
            assertTrue(event.getThrowableProxy() == null, "警告不该带栈");
        } finally {
            logger.detachAppender(appender);
            restore(previous);
        }
    }

    /**
     * 抓：配置里留了个空值就开始写件、报警。
     */
    @Test
    @DisplayName("空串当没给：不写任何件、不打日志")
    void blankPathIsTreatedAsNotGiven(@TempDir Path dir) throws IOException {
        String previous = System.getProperty(StateFile.PATH_PROPERTY);
        Logger logger = (Logger) LoggerFactory.getLogger(StateFile.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Set<String> before = namesNearWork();
            assertDoesNotThrow(() -> writeWith("  "), "空白值不该抛");
            assertDoesNotThrow(() -> writeWith(""), "空串不该抛");
            assertEquals(List.of(), allLines(appender), "空值当没给，一行日志都不该打");
            assertEquals(before, namesNearWork(), "空值当没给，工作目录与上级都不该多出件");
        } finally {
            logger.detachAppender(appender);
            restore(previous);
        }
    }

    private static void writeWith(String value) {
        System.setProperty(StateFile.PATH_PROPERTY, value);
        StateFile.write("ready");
    }

    /**
     * 抓：路径指到一个已有目录时，在目录旁边留下临时件。
     */
    @Test
    @DisplayName("目标是已有目录：不抛、有警告、不落临时件")
    void existingDirectoryWarnsAndLeavesNoTemp(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("folder");
        Files.createDirectory(target);
        String previous = System.getProperty(StateFile.PATH_PROPERTY);
        Logger logger = (Logger) LoggerFactory.getLogger(StateFile.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Set<String> before = tmpNamesUnder(target, target.getParent());
            System.setProperty(StateFile.PATH_PROPERTY, target.toString());
            assertDoesNotThrow(() -> StateFile.write("ready"), "目标是目录不该抛");
            assertEquals(before, tmpNamesUnder(target, target.getParent()), "目录与它的上级都不该多出临时件");
            assertEquals(1, appender.list.size(), "只该打一行日志");
            assertTrue(appender.list.get(0).getLevel() == Level.WARN, "这一行应是 WARN");
            assertTrue(appender.list.get(0).getThrowableProxy() == null, "警告不该带栈");
        } finally {
            logger.detachAppender(appender);
            restore(previous);
        }
    }

    /**
     * 抓：正常路径写出来的内容不对、或写完把临时件留在目录里。
     */
    @Test
    @DisplayName("正常路径：两行内容逐字对，目录里不留临时件")
    void normalPathWritesExactTwoLines(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("state.txt");
        String previous = System.getProperty(StateFile.PATH_PROPERTY);
        Logger logger = (Logger) LoggerFactory.getLogger(StateFile.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            System.setProperty(StateFile.PATH_PROPERTY, target.toString());
            assertDoesNotThrow(() -> StateFile.write("ready"), "正常路径不该抛");
            assertEquals(List.of(), allLines(appender), "写得进就不该打日志");
            String expected = "phase=ready" + "\n" + "pid=" + ProcessHandle.current().pid() + "\n";
            assertEquals(expected, Files.readString(target, StandardCharsets.UTF_8), "两行内容应逐字对上");
            assertEquals(Set.of(), tmpNamesUnder(dir), "目录里不该留临时件");
        } finally {
            logger.detachAppender(appender);
            restore(previous);
        }
    }

    private static void restore(String previous) {
        if (previous == null) {
            System.clearProperty(StateFile.PATH_PROPERTY);
        } else {
            System.setProperty(StateFile.PATH_PROPERTY, previous);
        }
    }

    private static List<String> allLines(ListAppender<ILoggingEvent> appender) {
        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            lines.add(event.getLevel() + " " + event.getFormattedMessage());
        }
        return lines;
    }

    /**
     * 工作目录与它上级里的件名单：临时件连长度与修改时刻一起记进串里，
     * 免得原来就有的同名件把这次落下的挡过去。
     */
    private static Set<String> namesNearWork() throws IOException {
        Set<String> names = new TreeSet<>();
        Path here = Path.of("").toAbsolutePath().normalize();
        collectNames(names, here);
        collectNames(names, here.getParent());
        return names;
    }

    private static Set<String> tmpNamesUnder(Path... dirs) throws IOException {
        Set<String> names = new TreeSet<>();
        for (Path dir : dirs) {
            collectTempNames(names, dir);
        }
        return names;
    }

    private static void collectNames(Set<String> names, Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                String key = dir + "/" + name;
                if (name.endsWith(".tmp")) {
                    key = key + "/" + entry.toFile().length() + "/" + entry.toFile().lastModified();
                }
                names.add(key);
            }
        }
    }

    private static void collectTempNames(Set<String> names, Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (name.endsWith(".tmp")) {
                    names.add(dir + "/" + name + "/" + entry.toFile().length() + "/" + entry.toFile().lastModified());
                }
            }
        }
    }
}
