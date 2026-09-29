package org.frostnova.nova.core.lang;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.stream.Stream;

/**
 * 按行追加的 JSONL 文件共用的读写辅助。
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
     * 坏的那半行本身照旧读不出来：读的一侧跳过坏行，读法见 {@link #lines(Path)}。
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

    /**
     * 按行读，解码遇到坏字节时替换成 U+FFFD 而不是抛。
     * <p>
     * 上面隔开的那半行里常有被切成半截的中文字。{@link Files#lines(Path, java.nio.charset.Charset)}
     * 读到它会抛 {@link UncheckedIOException}，于是<b>整份文件</b>从此读不出来，直到有人手修。
     * 这里只让那一行变成解析不了的样子，交给各处原有的坏行跳过；其余各行照读。
     * <p>
     * 与 {@link Files#lines(Path, java.nio.charset.Charset)} 一样须关闭；文件不在时抛
     * {@link java.nio.file.NoSuchFileException}，读的途中出错抛 {@link UncheckedIOException}。
     */
    public static Stream<String> lines(Path file) throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(file), decoder));
        return reader.lines().onClose(() -> {
            try {
                reader.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }
}
