package org.frostnova.nova.core.config;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.status.Status;
import org.frostnova.nova.core.NovaCoreApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LogStartupConfigTest {
    @TempDir
    Path temp;

    @Test
    void packagedConfigurationStartsWithoutWarnings() throws Exception {
        String output = start("defaults");
        assertTrue(output.contains("LOGGING_READY"), output);
        assertFalse(output.matches("(?s).*\\bDEBUG\\b.*"), output);
        assertTrue(output.contains("startup-info"), output);
        try (var paths = Files.walk(temp.resolve("defaults/logs"))) {
            String file = paths.filter(Files::isRegularFile).map(path -> {
                try {
                    return Files.readString(path);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).reduce("", String::concat);
            assertTrue(file.contains("startup-info"), file);
            assertFalse(file.contains("DEBUG"), file);
            System.out.println("Default startup file DEBUG lines: " + file.lines()
                    .filter(line -> line.contains("DEBUG")).count());
        }
        assertFalse(output.contains("STATUS_WARN"), output);
    }

    @Test
    void consoleAndFileLevelsRemainIndependent() throws Exception {
        String console = start("console", "DEBUG", "INFO");
        assertTrue(console.contains("startup-debug"), console);
        assertFalse(Files.readString(logFile(temp.resolve("console"))).contains("startup-debug"));
        String file = start("file", "INFO", "DEBUG");
        assertFalse(file.contains("startup-debug"), file);
        assertTrue(Files.readString(logFile(temp.resolve("file"))).contains("startup-debug"));
    }

    private Path logFile(Path dir) throws Exception {
        try (var paths = Files.walk(dir.resolve("logs"))) {
            return paths.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
    }

    private String start(String name, String... args) throws Exception {
        Path dir = Files.createDirectories(temp.resolve(name));
        if (args.length == 2) {
            Files.writeString(dir.resolve("application.yml"), "novabot:\n  core:\n    log:\n"
                    + "      console: " + args[0] + "\n      file: " + args[1] + "\n");
        }
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Path factories = classes.resolve("META-INF/spring.factories");
        Files.createDirectories(factories.getParent());
        Files.writeString(factories, "org.springframework.context.ApplicationListener="
                + Probe.class.getName() + "\n");
        // Copy only the probe; omit all test resources, especially logback-test.xml.
        for (Class<?> type : List.of(Probe.class, Probe.Warnings.class)) {
            String resource = type.getName().replace('.', '/') + ".class";
            Path probe = classes.resolve(resource);
            Files.createDirectories(probe.getParent());
            try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
                Files.copy(input, probe);
            }
        }
        Path config = Path.of("src/main/resources/logback-spring.xml").toAbsolutePath();
        Files.copy(config, classes.resolve(config.getFileName()));
        List<String> cp = new ArrayList<>();
        cp.add(classes.toString());
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            Path path = Path.of(entry).toAbsolutePath();
            if (Files.isDirectory(path)) {
                if (Files.exists(path.resolve("logback-test.xml")) || path.endsWith("test-classes")) {
                    continue;
                }
                // Isolate compiled production classes from stale or profile-specific resources.
                Path copy = Files.createDirectories(dir.resolve("cp-" + cp.size()));
                try (var files = Files.walk(path)) {
                    for (Path source : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                        Path target = copy.resolve(path.relativize(source));
                        Files.createDirectories(target.getParent());
                        Files.copy(source, target);
                    }
                }
                cp.add(copy.toString());
            } else {
                cp.add(path.toString());
            }
        }
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp", String.join(File.pathSeparator, cp), Probe.class.getName()));
        Path output = dir.resolve("stdout.txt");
        Process process = new ProcessBuilder(command).directory(dir.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Logging initialization timed out");
            String text = Files.readString(output);
            assertEquals(0, process.exitValue(), text);
            return text;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor();
            }
        }
    }

    public static class Probe implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {
        public static class Warnings implements ch.qos.logback.core.status.StatusListener {
            @Override
            public void addStatusEvent(Status status) {
                if (status.getLevel() >= Status.WARN) {
                    System.out.println("STATUS_WARN " + status.getMessage());
                }
            }
        }

        public static void main(String[] args) {
            System.setProperty("logback.statusListenerClass", Warnings.class.getName());
            NovaCoreApplication.main(args);
        }

        @Override
        public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            // Spring Boot resets the status listeners when it takes over logging.
            for (Status status : context.getStatusManager().getCopyOfStatusList()) {
                if (status.getLevel() >= Status.WARN) {
                    System.out.println("STATUS_WARN " + status.getMessage());
                }
            }
            var logger = LoggerFactory.getLogger("org.frostnova.nova.startup");
            logger.debug("startup-debug");
            logger.info("startup-info");
            context.stop();
            System.out.println("LOGGING_READY");
            System.exit(0);
        }
    }
}
