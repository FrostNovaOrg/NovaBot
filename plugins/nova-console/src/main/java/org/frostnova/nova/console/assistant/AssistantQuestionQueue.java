package org.frostnova.nova.console.assistant;

import org.frostnova.nova.core.assistant.AssistantException;
import org.frostnova.nova.core.assistant.AssistantProperties;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** 控制台和聊天共用的整问队列。设置保存、答完和退出队列都会唤醒等待者。 */
final class AssistantQuestionQueue {
    static final int WAITING_LIMIT = 6;
    static final String BUSY = "现在提问的人比较多，请稍后再问";
    private static final long WAIT_NANOS = TimeUnit.SECONDS.toNanos(90);
    private static final long HEARTBEAT_NANOS = TimeUnit.SECONDS.toNanos(2);

    interface Progress {
        Progress SILENT = ahead -> {};
        void queued(int ahead);
        default void heartbeat() {}
        default void started() {}
    }

    private final AssistantProperties properties;
    private final LongSupplier nanoTime;
    private final ArrayDeque<Ticket> waiting = new ArrayDeque<>();
    private int answering;
    private boolean closed;

    private static final class Ticket {
        final long deadline;
        Ticket(long deadline) { this.deadline = deadline; }
    }

    AssistantQuestionQueue(AssistantProperties properties, LongSupplier nanoTime) {
        this.properties = properties;
        this.nanoTime = nanoTime;
    }

    <T> T answer(Progress progress, Supplier<T> work) {
        boolean queued = enter(progress);
        try {
            if (queued) progress.started();
            return work.get();
        } finally {
            synchronized (properties) {
                answering--;
                properties.notifyAll();
            }
        }
    }

    private boolean enter(Progress progress) {
        Ticket ticket;
        int ahead;
        synchronized (properties) {
            checkOpen();
            long now = nanoTime.getAsLong();
            removeExpired(now);
            if (waiting.isEmpty() && answering < properties.getMaxConcurrentQuestions()) {
                answering++;
                return false;
            }
            if (waiting.size() >= WAITING_LIMIT) throw busy();
            ahead = answering + waiting.size();
            ticket = new Ticket(now + WAIT_NANOS);
            waiting.addLast(ticket);
        }
        try {
            progress.queued(ahead);
            while (true) {
                synchronized (properties) {
                    checkOpen();
                    long now = nanoTime.getAsLong();
                    removeExpired(now);
                    long remaining = ticket.deadline - now;
                    if (remaining <= 0) throw busy();
                    if (waiting.peekFirst() == ticket && answering < properties.getMaxConcurrentQuestions()) {
                        waiting.removeFirst();
                        answering++;
                        properties.notifyAll();
                        return true;
                    }
                    TimeUnit.NANOSECONDS.timedWait(properties, Math.min(remaining, HEARTBEAT_NANOS));
                }
                progress.heartbeat();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw busy();
        } finally {
            synchronized (properties) {
                if (waiting.remove(ticket)) properties.notifyAll();
            }
        }
    }

    private void removeExpired(long now) {
        if (waiting.removeIf(item -> item.deadline - now <= 0)) properties.notifyAll();
    }

    private void checkOpen() {
        if (closed || Thread.currentThread().isInterrupted()) throw busy();
    }

    private static AssistantException busy() { return new AssistantException("queue_busy", BUSY); }

    void close() {
        synchronized (properties) {
            closed = true;
            properties.notifyAll();
        }
    }
}
