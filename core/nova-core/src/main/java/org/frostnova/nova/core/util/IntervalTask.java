package org.frostnova.nova.core.util;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 按固定间隔反复跑的一件事，间隔可以在运行中换
 * <p>
 * 换间隔是「取消旧的、按新间隔排一个新的」。两条规矩保证换的那一下不出事：
 * <ul>
 *   <li><b>取消不打断</b>：正在跑的那一趟（比如正写到一半的存盘）照常跑完，只是不再排下一趟。
 *       打断它的话，写到一半的文件就是丢了的那一次。</li>
 *   <li><b>调度池只有一条线程</b>（由调用方给出，构造时不查）：新排的那一趟要等旧的那一趟跑完才轮得到，
 *       两趟不会同时跑。</li>
 * </ul>
 * 间隔为 0 或负数表示停排；之后再给正数就重新排上。
 */
public final class IntervalTask {
    private final ScheduledExecutorService scheduler;

    private final Runnable task;

    /**
     * 排上了没有：还没到该排的时候（数据没读完、读盘出了错）换间隔只记不排
     */
    private boolean armed;

    private ScheduledFuture<?> future;

    /**
     * @param scheduler 单线程的调度池
     * @param task 每一趟要做的事
     */
    public IntervalTask(ScheduledExecutorService scheduler, Runnable task) {
        this.scheduler = scheduler;
        this.task = task;
    }

    /**
     * 开始按间隔跑，第一趟在一个间隔之后
     * @param seconds 间隔，单位：秒；0 或负数时先不排，等以后换成正数
     */
    public synchronized void start(long seconds) {
        armed = true;
        schedule(seconds);
    }

    /**
     * 换间隔：取消旧的（不打断正在跑的那一趟），按新间隔重排，第一趟在一个新间隔之后
     * @param seconds 新间隔，单位：秒；0 或负数时停排
     * @return 重排了为 true；还没开始跑（{@link #start} 没调过）时什么也不做，为 false
     */
    public synchronized boolean reschedule(long seconds) {
        if (!armed) {
            return false;
        }
        if (future != null) {
            future.cancel(false);
            future = null;
        }
        schedule(seconds);
        return true;
    }

    private void schedule(long seconds) {
        if (seconds > 0 && !scheduler.isShutdown()) {
            future = scheduler.scheduleWithFixedDelay(task, seconds, seconds, TimeUnit.SECONDS);
        }
    }
}
