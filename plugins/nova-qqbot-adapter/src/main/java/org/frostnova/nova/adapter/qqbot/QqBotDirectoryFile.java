package org.frostnova.nova.adapter.qqbot;

import org.frostnova.nova.core.util.DurableFiles;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class QqBotDirectoryFile {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final Path path;

    QqBotDirectoryFile(Path directory) {
        path = directory.resolve("directory.json");
    }

    Map<String, QqBotDirectory.BotEntries> load() throws IOException {
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            return new LinkedHashMap<>();
        }
        QqBotStorage.protectDirectory(path.getParent());
        QqBotStorage.protectFile(path);
        return new LinkedHashMap<>(JSON.readValue(Files.readString(path), Data.class).bots());
    }

    void save(Map<String, QqBotDirectory.BotEntries> bots) throws IOException {
        QqBotStorage.prepareDirectory(path.getParent());
        QqBotStorage.protectFile(path);
        QqBotStorage.protectFile(DurableFiles.temporary(path));
        DurableFiles.replace(path, JSON.writeValueAsString(new Data(bots)), DurableFiles.OWNER_ONLY);
    }

    private record Data(Map<String, QqBotDirectory.BotEntries> bots) {}
}
