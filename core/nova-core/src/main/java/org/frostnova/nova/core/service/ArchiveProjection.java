package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSONReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** 复用字节缓冲区读取完整 JSONL 记录，投影时跳过不需要的字段。 */
final class ArchiveProjection {
    private byte[] buffer = new byte[64 * 1024];

    @FunctionalInterface
    interface RecordReader { void read(JSONReader reader) throws IOException; }

    void scan(Path path, RecordReader consumer) throws IOException {
        scan(path, consumer, false);
    }

    void scanLines(Path path, RecordReader consumer) throws IOException {
        scan(path, consumer, true);
    }

    private void scan(Path path, RecordReader consumer, boolean separateLines) throws IOException {
        try (var input = Files.newInputStream(path)) {
            int remaining = 0;
            boolean ended = false;
            while (!ended) {
                int read = input.read(buffer, remaining, buffer.length - remaining);
                ended = read < 0;
                int size = remaining + Math.max(0, read);
                int boundary = size;
                if (!ended) {
                    while (boundary > 0 && buffer[boundary - 1] != '\n' && buffer[boundary - 1] != '\r') boundary--;
                }
                if (boundary > 0) {
                    if (separateLines) {
                        int from = 0;
                        for (int i = 0; i < boundary; i++) {
                            if (buffer[i] == '\n' || buffer[i] == '\r') {
                                read(from, i - from, consumer, false);
                                from = i + 1;
                            }
                        }
                        if (from < boundary) read(from, boundary - from, consumer, false);
                    } else read(0, boundary, consumer, true);
                }
                remaining = size - boundary;
                if (remaining > 0) System.arraycopy(buffer, boundary, buffer, 0, remaining);
                // 仅当单条记录超过缓冲区时扩容，不随文件总大小增长。
                if (remaining == buffer.length) buffer = Arrays.copyOf(buffer, buffer.length * 2);
            }
        }
    }
    private void read(int from, int size, RecordReader consumer, boolean multiple) throws IOException {
        try (var reader = JSONReader.of(buffer, from, size)) {
            if (!reader.isEnd()) {
                consumer.read(reader);
                if (multiple) while (!reader.isEnd()) consumer.read(reader);
            }
        }
    }
}
