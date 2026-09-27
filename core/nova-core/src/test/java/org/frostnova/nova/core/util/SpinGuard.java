package org.frostnova.nova.core.util;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 打转判红用的时间盒
 * <p>
 * 起因链成环（A 的起因是 B、B 的起因又是 A）时，沿 {@code getCause()} 逐层找的循环不是
 * 卡住不动，而是纯 CPU 打转：中断拦不住它，{@code @Timeout} 走的正是中断，真打转时会把
 * 测试线程一起挂住，整盘就收不了尾。
 * <p>
 * 这里把被测那一步放进守护线程跑，到点没停就判红后放手：那条线程还会继续打转，
 * 但守护线程不拦 JVM 退出，判红的格不会把整盘挂住。
 */
public final class SpinGuard {

    /**
     * 一步给两秒：正常一步是微秒级，两秒没停就是在打转
     */
    public static final long STOP_MILLIS = 2000;

    private SpinGuard() {
    }

    /**
     * 跑一步，要求它在给定时限内自己停下；到点没停即判红
     *
     * @param millis 时限（毫秒）
     * @param step 被测那一步
     * @return 那一步的返回值
     * @throws Exception 那一步自己抛出来的，原样交回
     */
    public static <T> T stopsWithin(long millis, Callable<T> step) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                result.set(step.call());
            } catch (Throwable t) {
                thrown.set(t);
            }
        }, "打转判红");
        // 守护线程：真打转时这条线程停不下来，也不许它拦住整盘收尾
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
        if (worker.isAlive()) {
            throw new AssertionError("到 " + millis + " 毫秒还没停，原地打转了");
        }
        if (thrown.get() instanceof Exception e) {
            throw e;
        }
        if (thrown.get() instanceof Error e) {
            throw e;
        }
        return result.get();
    }
}
