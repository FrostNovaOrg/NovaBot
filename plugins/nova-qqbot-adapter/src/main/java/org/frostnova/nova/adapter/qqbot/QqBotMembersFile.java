package org.frostnova.nova.adapter.qqbot;

import org.frostnova.nova.core.util.DurableFiles;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

final class QqBotMembersFile {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final Path path;

    QqBotMembersFile(Path directory) {
        path = directory.resolve("members.json");
    }

    Map<String, QqBotMembers.BotMembers> load() throws IOException {
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            return Map.of();
        }
        QqBotStorage.protectDirectory(path.getParent());
        QqBotStorage.protectFile(path);
        return JSON.readValue(Files.readString(path), Data.class).bots();
    }

    void save(Map<String, QqBotMembers.BotMembers> bots) throws IOException {
        Path directory = path.getParent();
        QqBotStorage.prepareDirectory(directory);
        QqBotStorage.protectFile(path);
        Path temp;
        if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            temp = Files.createTempFile(directory, "members-", ".tmp",
                    PosixFilePermissions.asFileAttribute(DurableFiles.OWNER_ONLY));
        } else {
            temp = Files.createTempFile(directory, "members-", ".tmp");
        }
        try {
            QqBotStorage.protectFile(temp);
            ByteBuffer content = ByteBuffer.wrap(JSON.writeValueAsBytes(new Data(bots)));
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                while (content.hasRemaining()) {
                    channel.write(content);
                }
                channel.force(true);
            }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private record Data(Map<String, QqBotMembers.BotMembers> bots) {
        private Data {
            bots = Map.copyOf(bots);
        }
    }
}
