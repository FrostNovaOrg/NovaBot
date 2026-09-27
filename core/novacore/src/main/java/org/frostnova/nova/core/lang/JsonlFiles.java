package org.frostnova.nova.core.lang;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 按行追加的 JSONL 文件共用的写入辅助。
 * <p>
 * 场次归档、明细留档、主播快照、时间线、口令表都是同一形态：每行一条 JSON、只追加。
 * 这一形态的写者都逃不开同一个坑，所以补法只写在这里一份，各处不再各抄一遍。
 * 放在核心模块的 lang 里，是因为其中一处在核心（口令表）而其余在壳——
 * 壳依赖核心，核心用不到壳的件；放壳里那一份，核心这个调用方就够不着了。
 */
public final class JsonlFiles {
    private JsonlFiles() {
    }

    /**
     * 文件非空且最后一个字节不是换行时，先补一个换行。
     * <p>
     * 磁盘满或断电恰好卡在写一行的中间，盘上会留下没有换行的半行。
     * 下一次追加直接接在这半行后面，两条连成一行，<b>两条一起</b>读不出来——
     * 坏的半行不该连累紧跟着的那一条。追加前先补这一个换行，把它隔开。
     * 坏的那半行本身照旧读不出来：读的一侧跳过坏行，这里不改读法。
     */
    public static void separateTruncatedTail(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        long size = Files.size(file);
        if (size <= 0) {
            return;
        }
        byte[] one = new byte[1];
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            channel.position(size - 1);
            if (channel.read(ByteBuffer.wrap(one)) != 1) {
                return;
            }
        }
        if (one[0] != '\n') {
            Files.writeString(file, System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        }
    }
}
