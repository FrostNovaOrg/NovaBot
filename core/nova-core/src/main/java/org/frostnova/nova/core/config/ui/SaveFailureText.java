package org.frostnova.nova.core.config.ui;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 保存写不进文件时，把异常翻成给使用者看的一句话
 * <p>
 * 各保存口子把 {@code e.getMessage()} 直接接在「保存失败: 」后面回给界面。没权限或
 * 文件只读时这句答得很糟：{@link AccessDeniedException} 的 getMessage() 只是一个
 * 文件路径（Windows 上连「拒绝访问」的字样都没有），使用者看不出是权限的事，
 * 也不知道该去改哪里。这里把这一类失败翻成人话并点出文件；其余异常照旧回
 * getMessage()。各保存口子共用这一份翻法，不各写各的。
 */
public final class SaveFailureText {

    private SaveFailureText() {
    }

    /**
     * 翻译一次保存失败
     * <p>
     * 只读文件系统排在前面认：那种时候抛上来的往往也是 {@link AccessDeniedException}，
     * 而「改权限也救不了」这一点只有它说得清。原件动没动说不上确知，就一个字不提——
     * 说错比不说糟。
     * @param failure 保存时抛出的异常
     * @param file 想写进去的那个文件
     * @return 给使用者看的一句话；认得出的说清是没有写权限、文件只读还是文件系统只读，
     *         并点出是哪个文件；其余异常回它自己的 getMessage()
     */
    public static String explain(Exception failure, Path file) {
        String where = file.toAbsolutePath().normalize().toString();
        if (onReadOnlyFileSystem(file)) {
            return where + " 在只读的文件系统上，写不进，请检查它的挂载或所在卷的只读设置";
        }
        if (failure instanceof AccessDeniedException) {
            return "没有写权限或文件被设成了只读，写不进 " + where
                    + "，请检查运行程序的用户对这个文件和它所在目录的写权限（Windows 上还有文件属性里的只读）";
        }
        return failure.getMessage();
    }

    /**
     * 文件所在的文件系统是否只读；读不出来时按不是处理，回退到照旧回异常自己的消息
     */
    private static boolean onReadOnlyFileSystem(Path file) {
        try {
            return Files.getFileStore(file.toAbsolutePath().normalize()).isReadOnly();
        } catch (IOException | RuntimeException unavailable) {
            return false;
        }
    }
}
