package org.frostnova.nova.adapter.qqbot;

import org.frostnova.nova.core.util.DurableFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;

final class QqBotStorage {
    private static final LinkOption NO_LINKS = LinkOption.NOFOLLOW_LINKS;

    private QqBotStorage() {
    }

    static void prepareDirectory(Path directory) throws IOException {
        if (Files.notExists(directory, NO_LINKS)) {
            try {
                Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------")));
            } catch (UnsupportedOperationException e) {
                Files.createDirectories(directory);
            }
        }
        protectDirectory(directory);
    }

    static void protectDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory, NO_LINKS)) {
            throw new IOException("QQ 官方机器人数据目录不可用");
        }
        restrict(directory, true);
    }

    static void protectFile(Path path) throws IOException {
        if (!Files.notExists(path, NO_LINKS)) {
            if (!Files.isRegularFile(path, NO_LINKS)) {
                throw new IOException("QQ 官方机器人数据文件不可用");
            }
            restrict(path, false);
        }
    }

    private static void restrict(Path path, boolean directory) throws IOException {
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, NO_LINKS);
        if (posix != null) {
            posix.setPermissions(directory ? PosixFilePermissions.fromString("rwx------") : DurableFiles.OWNER_ONLY);
            return;
        }
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, NO_LINKS);
        if (acl == null) {
            throw new IOException("无法限制 QQ 官方机器人数据文件的访问权限");
        }
        var ownerAccess = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) {
            ownerAccess.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        }
        acl.setAcl(List.of(ownerAccess.build()));
    }
}
