package org.frostnova.nova.core.process;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;

/**
 * 测试拉起子进程用的启动类：拉起它的那个测试进程一没，子进程跟着退
 * <p>
 * 测试里另起一份 NovaBot（{@code java @参数文件}）时，收尾靠测试自己的 close()／finally
 * 去 destroyForcibly。测试进程被强杀（kill -9、超时被杀、检查被打断）时那些收尾一条都不执行，
 * 子进程就成了孤儿，开着端口、连着外网、写着日志一直跑下去。
 * <p>
 * 改为经本类起子进程：参数依次是拉起方的 pid、真正的主类、其余照转。本类先起一个守护线程
 * 盯住那个 pid，再把其余参数原样交给真正主类的 main。那个进程一不在了就立即 halt——
 * 不走关闭钩子，免得优雅停机卡住、孤儿又多活一截；锁件和端口由系统随进程收回。
 * <p>
 * 盯的是传进来的 pid，不取 {@code ProcessHandle.current().parent()}：拉起方若在本类启动前就没了，
 * 那时的父进程已经换成了 1 号进程，盯它等于永远不退。{@link ProcessHandle#isAlive()} 会比对
 * 进程的起始时间，pid 被别的进程复用也认得出。
 * <p>
 * 用法：参数文件里原来写主类名的那一行换成 {@link #mainLines(Class)} 的返回值。
 */
public final class ParentBoundMain {

    /**
     * 拉起方不在了时的退码
     */
    static final int PARENT_GONE_EXIT = 143;

    /**
     * 两次查看拉起方之间隔多久
     */
    private static final long POLL_MILLIS = 200;

    private ParentBoundMain() {
    }

    /**
     * 参数文件里替代主类名那一行的几行：本类、当前进程的 pid、真正的主类，每项一行
     */
    public static String mainLines(Class<?> target) {
        return mainLines(target.getName());
    }

    /**
     * 同 {@link #mainLines(Class)}，主类按名字给
     */
    public static String mainLines(String targetClassName) {
        return ParentBoundMain.class.getName() + "\n"
                + ProcessHandle.current().pid() + "\n"
                + targetClassName + "\n";
    }

    public static void main(String[] args) throws Throwable {
        if (args.length < 2) {
            System.err.println("用法：ParentBoundMain <拉起方 pid> <主类> [参数...]");
            System.exit(2);
        }
        Optional<ProcessHandle> parent = ProcessHandle.of(Long.parseLong(args[0]));
        if (parent.isEmpty() || !parent.get().isAlive()) {
            Runtime.getRuntime().halt(PARENT_GONE_EXIT);
        }
        ProcessHandle watched = parent.get();
        Thread watch = new Thread(() -> {
            while (watched.isAlive()) {
                try {
                    Thread.sleep(POLL_MILLIS);
                } catch (InterruptedException e) {
                    return;
                }
            }
            Runtime.getRuntime().halt(PARENT_GONE_EXIT);
        }, "parent-watch");
        watch.setDaemon(true);
        watch.start();

        Method main = Class.forName(args[1]).getMethod("main", String[].class);
        try {
            main.invoke(null, (Object) Arrays.copyOfRange(args, 2, args.length));
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
