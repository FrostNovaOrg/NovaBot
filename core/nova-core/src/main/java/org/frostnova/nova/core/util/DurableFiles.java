package org.frostnova.nova.core.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * 把一份小文件换上去，或把读坏的原件改名留底。
 * <p>
 * 直接往目标上写是先截断再写：磁盘满或写到一半断电，文件就空了或只剩半截，
 * 重启之后只看得见一份空的。所以先把内容写进同目录的临时文件、刷盘，再改名换上。
 * 临时文件名固定跟着目标走，不另起一串带随机数的名字，失败重试也不会越积越多。
 * <p>
 * 换名这条路自己也有几个边，都由本类兜着，调用方不必各自处理：
 * <ul>
 * <li>权限——临时文件按原件的权限创建；原件还没有时按调用方给的新建默认
 *     （含秘密的件传 {@link #OWNER_ONLY}，其余传 {@code null} 跟系统默认走），
 *     从建出来那一刻起就不比别人能读的多，不存在中间宽一下的窗口；
 * <li>建临时文件被拒——目录只读或权限不够时建不出临时文件，这时退回直接写
 *     并记一条 WARN：存不上比换不上更糟。只限这两类失败；磁盘满这一类直接写
 *     同样写不进，照常抛出、不退回，免得退回先把原件截断掉；
 * <li>换名被拒——目标被单独挂载进容器（把配置文件一个文件挂进去）时改名不成，
 *     同样退回直接写并记一条 WARN；
 * <li>属主属组——换名会换 inode，属主属组跟着临时文件的那一份走。与原件对不上、
 *     原件的安排（如「管理员组可编辑」的 0660 配置文件）会丢时，也退回直接写：
 *     原地写不动 inode，原件的属主属组原样保住；
 * <li>符号链接——目标是链接时写它指向的真文件；指向的件还不存在（悬空链接）也
 *     顺着解析过去把它建出来，不把链接顶成普通文件。
 * </ul>
 */
@Slf4j
public final class DurableFiles {
    private static final DateTimeFormatter BAD_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneId.systemDefault());

    /**
     * 系统解析符号链接的层数上限，成环的链接照这个数断掉
     */
    private static final int LINK_DEPTH_LIMIT = 40;

    /**
     * 新建秘密文件时的默认权限：仅属主可读写，宁紧勿松。
     * 配置文件与登录凭据这一类件在「原件还没有」时用它建，其余件跟系统默认走
     */
    public static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private DurableFiles() {
    }

    /**
     * 用一份完整内容换上目标文件，新建件跟系统默认权限走。
     * <p>
     * 等价于 {@code replace(target, content, null)}。
     * @param target 要换上的文件
     * @param content 完整内容
     * @throws IOException 临时文件写不进去（如磁盘写满）或最终没写成时抛出；换名那条路失败时目标文件保持原样，
     *                     退回直接写失败时已尽力写回原样（见 {@link #replace(Path, String, Set)}）
     */
    public static void replace(Path target, String content) throws IOException {
        replace(target, content, null);
    }

    /**
     * 用一组行换上目标文件，语义与 {@code Files.write(path, lines, UTF_8)} 一致：
     * 每行后面跟平台换行符，空列表写出空文件。新建件跟系统默认权限走。
     * @param target 要换上的文件
     * @param lines 完整的行
     * @throws IOException 临时文件写不进去（如磁盘写满）或最终没写成时抛出；换名那条路失败时目标文件保持原样，
     *                     退回直接写失败时已尽力写回原样（见 {@link #replace(Path, String, Set)}）
     */
    public static void replace(Path target, List<String> lines) throws IOException {
        replace(target, lines, null);
    }

    /**
     * 用一组行换上目标文件，新建件按指定权限建。
     * @param target 要换上的文件
     * @param lines 完整的行
     * @param newFilePermissions 新建件的默认权限，{@code null} 表示跟系统默认（umask）走
     * @throws IOException 临时文件写不进去（如磁盘写满）或最终没写成时抛出；换名那条路失败时目标文件保持原样，
     *                     退回直接写失败时已尽力写回原样（见 {@link #replace(Path, String, Set)}）
     */
    public static void replace(Path target, List<String> lines, Set<PosixFilePermission> newFilePermissions)
            throws IOException {
        StringBuilder content = new StringBuilder();
        for (String line : lines) {
            content.append(line).append(System.lineSeparator());
        }
        replace(target, content.toString(), newFilePermissions);
    }

    /**
     * 用一份完整内容换上目标文件。
     * <p>
     * 换名尽量是原子的。文件系统不支持原子改名时，退回普通替换；建临时文件被拒
     * （目录只读、权限不够）、换名被拒（如目标被单独挂载）或换名保不住原件的属主
     * 属组时，退回直接写并各记一条 WARN；建件失败里磁盘满这一类直接写同样写不进，
     * 照常抛出。
     * <p>
     * 直接写是先截断再写，写到一半出错目标就只剩半截。所以直接写之前先把原件整份读进
     * 内存，写失败时尽力写回原字节；原来那个写失败的错照常抛出，写回也失败时写回的错
     * 挂在它的 suppressed 上、日志点名那份文件可能已不完整。不另建副本：副本会把
     * 旧内容（可能含明文）多留一份，而走到直接写这一支，常常正是因为同目录建不了件。
     * @param target 要换上的文件
     * @param content 完整内容
     * @param newFilePermissions 新建件的默认权限，{@code null} 表示跟系统默认（umask）走；
     *                           原件已在时本参数不用，权限照原件
     * @throws IOException 临时文件写不进去（如磁盘写满）或最终没写成时抛出。换名那条路失败时目标文件
     *                     保持原样；退回直接写失败时已尽力写回原样，写回也失败时目标可能不完整
     */
    public static void replace(Path target, String content, Set<PosixFilePermission> newFilePermissions)
            throws IOException {
        // 目标是符号链接时，写到它指向的那个真文件上，而不是把链接顶成普通文件
        target = resolveTarget(target);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Path temp = temporary(target);
        try {
            createTempWithPermissions(temp, permissionsOf(target, newFilePermissions));
        } catch (IOException createFailed) {
            if (!directWriteStillPossible(createFailed, temp)) {
                // 磁盘满这一类失败，直接写同样写不进：退回只会先把原件截断、再写不进，
                // 留下一份空的。照常抛出，原件原样不动
                throw createFailed;
            }
            // 到这里说明只是建不出临时文件（权限、只读文件系统），直接写还在行
            log.warn("建临时文件失败, 退回直接写 {}: {}", target, createFailed.toString());
            writeInPlace(target, content);
            return;
        }
        String ownershipChange = ownershipChangeByMove(target, temp);
        if (ownershipChange != null) {
            // 换名会换 inode，属主属组跟着临时文件的那一份走；原件的安排
            // （如「管理员组可编辑」的 0660 配置文件）会丢时退回直接写，原地写不动 inode
            log.warn("换名会丢掉原件的属主或属组, 退回直接写 {}: {}", target, ownershipChange);
            writeInPlace(target, content);
            deleteTempQuietly(temp);
            return;
        }
        try (FileChannel channel = FileChannel.open(temp,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        try {
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException moveFailed) {
            log.warn("换名失败, 退回直接写 {}: {}", target, moveFailed.toString());
            writeInPlace(target, content);
            deleteTempQuietly(temp);
        }
    }

    /**
     * 退回的直接写：先截断再写，写到一半出错目标就只剩半截，所以写之前把原件整份
     * 读进内存，写失败时尽力写回原字节。
     * <p>
     * 原件不在就没有可退的，照旧写。原件在但读不出（如只写不读的权限）时也照旧写——
     * 为了能写回而拒写，会把本来存得上的保存变成存不上；这时写失败就说不出原样了，
     * 日志照「可能已不完整」报。无论写回成没成，原来那个写失败的错都照抛给调用方。
     */
    private static void writeInPlace(Path target, String content) throws IOException {
        byte[] original = null;
        IOException unreadable = null;
        try {
            original = Files.readAllBytes(target);
        } catch (NoSuchFileException absent) {
            // 原件还没有：写失败也没有要写回的
        } catch (IOException readFailed) {
            unreadable = readFailed;
        }
        try {
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException writeFailed) {
            if (original != null) {
                try {
                    Files.write(target, original);
                    log.warn("直接写 {} 失败, 已写回原样: {}", target, writeFailed.toString());
                } catch (IOException restoreFailed) {
                    writeFailed.addSuppressed(restoreFailed);
                    log.error("直接写 {} 失败, 写回原文也失败, 原件可能已不完整: {}; 写回: {}",
                            target, writeFailed.toString(), restoreFailed.toString());
                }
            } else if (unreadable != null) {
                log.error("直接写 {} 失败, 写之前原件没读出来、无从写回, 原件可能已不完整: {}; 读原件: {}",
                        target, writeFailed.toString(), unreadable.toString());
            }
            throw writeFailed;
        }
    }

    /**
     * 把目标解析到它指向的真文件；目标还没有时按原路径写。
     * <p>
     * toRealPath 在件还不存在时失败，其中悬空链接（指向的件还没建出来）不能跟着
     * 退回链接本身——那样换名会把链接顶成普通文件、指向的件也没建出来，比直接写
     * 还糟。所以失败后再顺着链接一层层解析到最终指向（还不存在的）那个路径；
     * 成环或层级太深的链接按系统同样的办法断掉。
     */
    private static Path resolveTarget(Path target) throws IOException {
        try {
            return target.toRealPath();
        } catch (IOException notReal) {
            Path current = target;
            for (int followed = 0; Files.isSymbolicLink(current); followed++) {
                if (followed >= LINK_DEPTH_LIMIT) {
                    throw new FileSystemException(target.toString(), null,
                            "符号链接成环或层级超过 " + LINK_DEPTH_LIMIT);
                }
                Path linkTarget = Files.readSymbolicLink(current);
                Path base = current.getParent();
                current = linkTarget.isAbsolute() || base == null ? linkTarget : base.resolve(linkTarget);
                current = current.normalize();
            }
            return current;
        }
    }

    /**
     * 原件的权限。原件还没有时按调用方给的新建默认（{@code null} 表示跟系统默认走）；
     * 原件在但读不到权限时按仅属主可读写兜底
     */
    private static Set<PosixFilePermission> permissionsOf(Path target, Set<PosixFilePermission> newFilePermissions) {
        try {
            return Files.getPosixFilePermissions(target);
        } catch (NoSuchFileException notThere) {
            return newFilePermissions;
        } catch (IOException | UnsupportedOperationException | SecurityException unreadable) {
            return OWNER_ONLY;
        }
    }

    /**
     * 建临时文件失败里，哪些只说明「换名这条路走不通，直接写还在行」：
     * 权限不够与只读文件系统两类。
     * <p>
     * 权限不够看异常类型：EACCES／EPERM 在 Linux、macOS 上都映射成
     * {@link AccessDeniedException}，Windows 拒绝建件也是它，各平台一致。
     * 只读文件系统看挂载标志：临时件所在目录的
     * {@link Files#getFileStore(Path)} 读 {@code isReadOnly()}（Linux 上来自
     * statvfs 的 ST_RDONLY，Windows 上来自卷的只读属性），跟系统语言环境和
     * 目录路径里的字样都无关。不看报错文字，因为文字两头都靠不住：一是
     * {@link FileSystemException} 的 {@code getMessage()} 把「文件路径: 原因」
     * 连成一串，目录路径里带 read-only 一类字样时，磁盘满也会被认成只读、
     * 退回直接写把原件截成空；二是原因串随系统语言环境变（装了译文包的
     * 中文 Linux 上 EROFS 的文字是「只读文件系统」），认不出。也不按
     * {@code getReason()} 留文字兜底：EROFS 的文字与挂载标志说的是同一件事，
     * 同一条件摆两道认法会互相遮住失效，留可靠的这道就够了。API 里另有
     * {@link java.nio.file.ReadOnlyFileSystemException}，但它是运行期异常、只读的
     * zip 一类文件系统在用，默认文件系统建件不会抛它，到不了这里。
     * 取挂载标志这一步自己出错时，按退不了回处理：照常抛原来那个异常——
     * 认不出宁可多抛一次，不冒退回错一次把原件截空的险。
     * 其余失败（磁盘满 ENOSPC、目录项用尽、IO 错误等）不在其列：那时候直接写
     * 同样写不进，退回只会先把原件截断掉。
     */
    private static boolean directWriteStillPossible(IOException createFailed, Path temp) {
        if (createFailed instanceof AccessDeniedException) {
            return true;
        }
        try {
            return Files.getFileStore(temp.getParent()).isReadOnly();
        } catch (IOException | RuntimeException mountFlagUnavailable) {
            return false;
        }
    }

    /**
     * 换名会换 inode：换上之后，属主与属组就是临时文件的那一份（进程用户与它的主组）。
     * @return 原件的属主或属组会跟着换掉的说明；两样都保得住，或读不到没法比时返回 {@code null}
     */
    private static String ownershipChangeByMove(Path target, Path temp) {
        PosixFileAttributes original;
        PosixFileAttributes replacement;
        try {
            original = Files.readAttributes(target, PosixFileAttributes.class);
            replacement = Files.readAttributes(temp, PosixFileAttributes.class);
        } catch (IOException | UnsupportedOperationException | SecurityException noComparison) {
            return null;
        }
        boolean ownerDiffers = !original.owner().getName().equals(replacement.owner().getName());
        boolean groupDiffers = !original.group().getName().equals(replacement.group().getName());
        if (!ownerDiffers && !groupDiffers) {
            return null;
        }
        return "属主 " + original.owner().getName() + " -> " + replacement.owner().getName()
                + ", 属组 " + original.group().getName() + " -> " + replacement.group().getName();
    }

    /**
     * 退回直接写之后删掉临时文件
     */
    private static void deleteTempQuietly(Path temp) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            // 删不掉就留着：临时文件名固定，下次写之前会被复用，不会越积越多
        }
    }

    /**
     * 按指定权限创建临时文件。带属性创建在 POSIX 上是原子的：
     * 文件一出现就是这份权限，不存在先按默认建出来再收紧的宽窗口。
     * <p>
     * {@code null} 表示不带属性建，跟系统默认（umask）走——与直接写出来的权限一样；
     * 临时文件已经存在（上次失败的残留）时复用它并把权限重新对齐；
     * 非 POSIX 文件系统（如 Windows）带不了属性，退化为普通创建。
     */
    private static void createTempWithPermissions(Path temp, Set<PosixFilePermission> permissions)
            throws IOException {
        if (permissions == null) {
            createTemp(temp);
            return;
        }
        try {
            Files.createFile(temp, PosixFilePermissions.asFileAttribute(permissions));
        } catch (FileAlreadyExistsException e) {
            try {
                Files.setPosixFilePermissions(temp, permissions);
            } catch (IOException | UnsupportedOperationException alignFailed) {
                // 对不齐也继续：残留复用本就是少见路径，别让它把保存顶死
            }
        } catch (UnsupportedOperationException e) {
            createTemp(temp);
        }
    }

    /**
     * 不带属性建临时文件；已存在（上次失败的残留）时直接复用
     */
    private static void createTemp(Path temp) throws IOException {
        try {
            Files.createFile(temp);
        } catch (FileAlreadyExistsException reuse) {
            // 上次失败的残留，直接复用
        }
    }

    /**
     * 把读坏的文件改名留在原目录，字节不动。
     * <p>
     * 名字是「原名.bad-时间」。同一毫秒里已经有一份时，后面加序号，不盖掉更早的那份。
     * @param source 坏文件
     * @return 留底之后的路径
     * @throws IOException 改名失败时抛出，坏文件仍在原处
     */
    public static Path quarantine(Path source) throws IOException {
        String stamp = BAD_STAMP.format(Instant.now());
        String prefix = source.getFileName() + ".bad-" + stamp;
        Path destination = source.resolveSibling(prefix);
        int extra = 2;
        while (Files.exists(destination)) {
            destination = source.resolveSibling(prefix + "-" + extra);
            extra++;
        }
        Files.move(source, destination);
        return destination;
    }

    /**
     * 临时文件与目标同目录，名字是目标文件名加 {@code .tmp}。
     */
    public static Path temporary(Path target) {
        Path name = target.getFileName();
        if (name == null) {
            throw new IllegalArgumentException("没有文件名: " + target);
        }
        return target.resolveSibling(name.toString() + ".tmp");
    }
}
