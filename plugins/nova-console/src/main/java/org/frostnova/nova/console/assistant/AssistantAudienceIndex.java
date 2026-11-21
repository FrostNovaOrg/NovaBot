package org.frostnova.nova.console.assistant;

import org.frostnova.nova.core.util.DurableFiles;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UTFDataFormatException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongToIntFunction;

/** 本机派生文件。按观众编号排序的定长记录和场次进度一起原子替换。 */
final class AssistantAudienceIndex {
    static final int NO_DAY = Integer.MAX_VALUE;
    private static final int MAGIC = 0x4e415648;
    private static final int VERSION = 1;
    private static final int PREFIX_BYTES = 2 * Integer.BYTES;
    private static final int ROW_BYTES = Long.BYTES + Integer.BYTES;
    private static final int PAGE_ROWS = 512;
    private static final int CACHE_PAGES = 128;

    record FileStamp(long size, Instant modified, String key) {
        static FileStamp read(Path path) throws IOException {
            try {
                var attrs = Files.readAttributes(path, BasicFileAttributes.class);
                return new FileStamp(attrs.size(), attrs.lastModifiedTime().toInstant(),
                        Objects.toString(attrs.fileKey(), ""));
            } catch (NoSuchFileException ignored) {
                return null;
            }
        }

        private static FileStamp read(DataInputStream input) throws IOException {
            if (!input.readBoolean()) return null;
            return new FileStamp(input.readLong(), Instant.ofEpochSecond(input.readLong(), input.readInt()), input.readUTF());
        }

        private static void write(DataOutputStream out, FileStamp stamp) throws IOException {
            out.writeBoolean(stamp != null);
            if (stamp == null) return;
            out.writeLong(stamp.size);
            out.writeLong(stamp.modified.getEpochSecond());
            out.writeInt(stamp.modified.getNano());
            out.writeUTF(stamp.key);
        }
    }

    record SessionStamp(String summary, FileStamp events, FileStamp danmu) {}

    record Progress(FileStamp source, long liveStart, boolean complete, int earliestDay, long invalidLines,
                    Map<Long, SessionStamp> sessions) {}

    static View open(Path path) throws IOException {
        FileChannel channel;
        try {
            channel = FileChannel.open(path, StandardOpenOption.READ);
        } catch (NoSuchFileException ignored) {
            return null;
        }
        boolean opened = false;
        try {
            long size = channel.size();
            if (size < PREFIX_BYTES + Long.BYTES) return null;
            ByteBuffer word = ByteBuffer.allocate(Long.BYTES);
            read(channel, word, 0);
            if (word.getInt() != MAGIC || word.getInt() != VERSION) return null;
            word.clear();
            read(channel, word, size - Long.BYTES);
            long offset = word.getLong();
            if (offset < PREFIX_BYTES || offset > size - Long.BYTES || (offset - PREFIX_BYTES) % ROW_BYTES != 0)
                return null;
            channel.position(offset);
            var in = input(channel);
            FileStamp source = FileStamp.read(in);
            long liveStart = in.readLong();
            boolean complete = in.readBoolean();
            int earliestDay = in.readInt();
            long invalidLines = in.readLong();
            int count = in.readInt();
            if (count < 0 || count > (size - offset) / (Long.BYTES + Short.BYTES + 2)) return null;
            Map<Long, SessionStamp> sessions = new HashMap<>();
            for (int i = 0; i < count; i++) {
                long start = in.readLong();
                var stamp = new SessionStamp(in.readUTF(), FileStamp.read(in), FileStamp.read(in));
                if (sessions.put(start, stamp) != null) return null;
            }
            if (in.readLong() != offset || in.read() != -1) return null;
            var view = new View(channel, (offset - PREFIX_BYTES) / ROW_BYTES,
                    new Progress(source, liveStart, complete, earliestDay, invalidLines, sessions));
            opened = true;
            return view;
        } catch (EOFException | UTFDataFormatException | DateTimeException ignored) {
            return null;
        } finally {
            if (!opened) channel.close();
        }
    }

    static void save(Path path, Progress progress, View base, long[] newUids, LongToIntFunction day) throws IOException {
        Path directory = path.getParent();
        Files.createDirectories(directory);
        Path temp = privateTempFile(directory);
        try {
            try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE);
                 var out = new DataOutputStream(new BufferedOutputStream(Channels.newOutputStream(channel), 32_768))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                long rows = merge(out, base, newUids, day);
                FileStamp.write(out, progress.source);
                out.writeLong(progress.liveStart);
                out.writeBoolean(progress.complete);
                out.writeInt(progress.earliestDay);
                out.writeLong(progress.invalidLines);
                out.writeInt(progress.sessions.size());
                for (var entry : progress.sessions.entrySet()) {
                    var stamp = entry.getValue();
                    out.writeLong(entry.getKey());
                    out.writeUTF(stamp.summary);
                    FileStamp.write(out, stamp.events);
                    FileStamp.write(out, stamp.danmu);
                }
                out.writeLong(PREFIX_BYTES + rows * ROW_BYTES);
                out.flush();
                channel.force(true);
            }
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static long merge(DataOutputStream out, View base, long[] newUids, LongToIntFunction day) throws IOException {
        var old = new Cursor(base);
        long rows = 0;
        for (long uid : newUids) {
            while (old.uid != 0 && old.uid < uid) {
                writeRow(out, old.uid, old.day);
                old.next();
                rows++;
            }
            int first = day.applyAsInt(uid);
            if (old.uid == uid) {
                first = Math.min(first, old.day);
                old.next();
            }
            writeRow(out, uid, first);
            rows++;
        }
        while (old.uid != 0) {
            writeRow(out, old.uid, old.day);
            old.next();
            rows++;
        }
        return rows;
    }

    private static void writeRow(DataOutputStream out, long uid, int day) throws IOException {
        out.writeLong(uid);
        out.writeInt(day);
    }

    private static Path privateTempFile(Path directory) throws IOException {
        try {
            return Files.createTempFile(directory, "audience-", ".tmp",
                    PosixFilePermissions.asFileAttribute(DurableFiles.OWNER_ONLY));
        } catch (UnsupportedOperationException ignored) {
            Path path = Files.createTempFile(directory, "audience-", ".tmp");
            var file = path.toFile();
            if (file.setReadable(false, false) && file.setWritable(false, false)
                    && file.setReadable(true, true) && file.setWritable(true, true)) return path;
            Files.deleteIfExists(path);
            throw new IOException("观众首次出现记录文件权限设置失败");
        }
    }

    private static DataInputStream input(FileChannel channel) {
        return new DataInputStream(new BufferedInputStream(Channels.newInputStream(channel), 32_768));
    }

    private static void read(FileChannel channel, ByteBuffer buffer, long offset) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, offset + buffer.position()) < 0) throw new EOFException();
        }
        buffer.flip();
    }

    private static final class Cursor {
        private final DataInputStream in;
        private long remaining;
        private long uid;
        private int day;

        Cursor(View view) throws IOException {
            if (view == null) {
                in = null;
            } else {
                view.channel.position(PREFIX_BYTES);
                in = input(view.channel);
                remaining = view.rows;
                next();
            }
        }

        void next() throws IOException {
            if (remaining == 0) {
                uid = 0;
                return;
            }
            uid = in.readLong();
            day = in.readInt();
            remaining--;
        }
    }

    static final class View implements AutoCloseable {
        private final FileChannel channel;
        private final long rows;
        final Progress progress;
        private long[] pageKeys;
        private final ByteBuffer[] pages = new ByteBuffer[CACHE_PAGES];
        private final int[] pageIds = new int[CACHE_PAGES];

        private View(FileChannel channel, long rows, Progress progress) {
            this.channel = channel;
            this.rows = rows;
            this.progress = progress;
        }

        int firstDay(long uid) throws IOException {
            if (rows == 0) return NO_DAY;
            if (pageKeys == null) loadPageKeys();
            int page = Arrays.binarySearch(pageKeys, uid);
            if (page < 0) page = -page - 2;
            if (page < 0) return NO_DAY;
            ByteBuffer records = page(page);
            int low = 0, high = records.limit() / ROW_BYTES - 1;
            while (low <= high) {
                int middle = (low + high) >>> 1;
                int pos = middle * ROW_BYTES;
                long key = records.getLong(pos);
                if (key == uid) return records.getInt(pos + Long.BYTES);
                if (key < uid) low = middle + 1;
                else high = middle - 1;
            }
            return NO_DAY;
        }

        private void loadPageKeys() throws IOException {
            var keys = new long[Math.toIntExact((rows - 1) / PAGE_ROWS + 1)];
            var word = ByteBuffer.allocate(Long.BYTES);
            for (int page = 0; page < keys.length; page++) {
                word.clear();
                read(channel, word, offset(page));
                keys[page] = word.getLong();
            }
            pageKeys = keys;
        }

        private ByteBuffer page(int page) throws IOException {
            int slot = page % CACHE_PAGES;
            ByteBuffer buffer = pages[slot];
            if (buffer != null && pageIds[slot] == page) return buffer;
            if (buffer == null) buffer = ByteBuffer.allocate(PAGE_ROWS * ROW_BYTES);
            buffer.clear();
            buffer.limit((int) Math.min(PAGE_ROWS, rows - (long) page * PAGE_ROWS) * ROW_BYTES);
            read(channel, buffer, offset(page));
            pages[slot] = buffer;
            pageIds[slot] = page;
            return buffer;
        }

        private static long offset(int page) {
            return PREFIX_BYTES + (long) page * PAGE_ROWS * ROW_BYTES;
        }

        @Override public void close() throws IOException { channel.close(); }
    }
}
