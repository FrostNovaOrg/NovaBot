package org.frostnova.nova.core.lang;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

/**
 * 按行读的文件里跳过坏行时的提示
 * <p>
 * 坏行一直留在文件里，每读一次都会再撞上；照撞照报会把日志刷满。
 * 同一份文件（路径、修改时刻与大小都没变）只警告第一次，文件变了再警告，其余记 debug。
 * <p>
 * 只记异常类名，不记异常文字：JSON 解析器的异常文字常附带输入原文，
 * 行里的东西（口令哈希、签给谁、观众 uid 名单）会跟着进日志，
 * 使用者把日志贴给别人求助时一并贴了出去。
 */
public final class BadLineNotice {
    private final Logger log;
    private final String message;

    /**
     * 上次警告过坏行时文件的样子
     */
    private volatile FileState warned;

    /**
     * @param log 提示记在谁的日志里
     * @param message 提示的开头，如「跳过口令表中无法解析的一行」
     */
    public BadLineNotice(Logger log, String message) {
        this.log = log;
        this.message = message;
    }

    /**
     * 读一趟之前文件的样子
     * @return 取不到属性时为 {@code null}，此时每次都警告
     */
    public static FileState stateOf(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return new FileState(path, attributes.lastModifiedTime(), attributes.size());
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 跳过了一行
     * @param state 读这一趟之前文件的样子，见 {@link #stateOf(Path)}
     * @param e 解析这一行时抛出的异常
     */
    public void skipped(FileState state, Exception e) {
        String reason = e.getClass().getSimpleName();
        if (state != null && state.equals(warned)) {
            log.debug("{}: {}", message, reason);
        } else {
            warned = state;
            log.warn("{}: {}（文件有变动之前不再重复提示）", message, reason);
        }
    }

    /**
     * 文件的样子：路径、修改时刻与大小
     */
    public record FileState(Path path, FileTime modified, long size) {
    }
}
