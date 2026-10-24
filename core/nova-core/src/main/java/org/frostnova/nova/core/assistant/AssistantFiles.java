package org.frostnova.nova.core.assistant;

import org.frostnova.nova.core.util.DurableFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 智能助手状态文件统一按仅属主可读写保存。 */
final class AssistantFiles {
    private AssistantFiles() { }

    static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        restrict(path);
        DurableFiles.replace(path, content, DurableFiles.OWNER_ONLY);
        restrict(path);
    }

    static void restrict(Path path) throws IOException {
        if (Files.exists(path)) {
            try { Files.setPosixFilePermissions(path, DurableFiles.OWNER_ONLY); }
            catch (UnsupportedOperationException ignored) {
                var file = path.toFile();
                if (!file.setReadable(false, false) || !file.setWritable(false, false)
                        || !file.setReadable(true, true) || !file.setWritable(true, true)) {
                    throw new IOException("智能助手文件权限设置失败");
                }
            }
        }
    }
}
