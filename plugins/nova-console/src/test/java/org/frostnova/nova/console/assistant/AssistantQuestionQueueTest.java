package org.frostnova.nova.console.assistant;

import org.frostnova.nova.core.assistant.AssistantException;
import org.frostnova.nova.core.assistant.AssistantProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

class AssistantQuestionQueueTest {
    private final AssistantProperties settings = new AssistantProperties();
    private final AtomicLong nanos = new AtomicLong();
    private final AssistantQuestionQueue queue = new AssistantQuestionQueue(settings, nanos::get);
    private final ExecutorService callers = Executors.newFixedThreadPool(10);

    private final LinkedBlockingQueue<Question> starts = new LinkedBlockingQueue<>();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();

    @AfterEach void stop() throws InterruptedException {
        queue.close();
        callers.shutdownNow();
        assertTrue(callers.awaitTermination(10, SECONDS));
    }

    private final class Question {
        final CompletableFuture<Integer> ahead = new CompletableFuture<>();
        final CompletableFuture<Void> started = new CompletableFuture<>();
        final CountDownLatch finish = new CountDownLatch(1);
        final Future<?> result = callers.submit(() -> queue.answer(ahead::complete, () -> {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            started.complete(null);
            starts.add(this);
            try {
                assertTrue(finish.await(30, SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
            return null;
        }));

        void expectStarted() throws InterruptedException { assertSame(this, starts.poll(10, SECONDS)); }
        void expectAhead(int count) throws Exception { assertEquals(count, ahead.get(10, SECONDS)); }
        void complete() throws Exception {
            finish.countDown();
            result.get(10, SECONDS);
        }
    }

    private Question running() throws Exception {
        var question = new Question();
        question.expectStarted();
        assertFalse(question.ahead.isDone(), "空闲时不发排队提示");
        return question;
    }

    private Question waiting(int ahead) throws Exception {
        var question = new Question();
        question.expectAhead(ahead);
        return question;
    }

    @Test void sixWaitInArrivalOrderAndTheSeventhIsRefusedBeforeWork() throws Exception {
        var first = running();
        var second = running();
        var waiting = new ArrayList<Question>();
        for (int i = 0; i < 6; i++) waiting.add(waiting(2 + i));
        var full = assertThrows(AssistantException.class, () -> queue.answer(
                ahead -> fail("队满时不应入队"), () -> fail("队满时不应开始查数或记账")));
        assertEquals("现在提问的人比较多，请稍后再问", full.getMessage());

        first.complete();
        for (var question : waiting) {
            question.expectStarted();
            question.complete();
        }
        second.complete();
        assertEquals(2, peak.get());
    }

    @Test void raisingThenLoweringTheLimitWakesWaitersAndLetsActiveQuestionsFinish() throws Exception {
        var first = running();
        var second = running();
        var third = waiting(2);
        var fourth = waiting(3);

        settings.setMaxConcurrentQuestions(3);
        third.expectStarted();
        settings.setMaxConcurrentQuestions(1);
        third.complete();
        first.complete();
        var fifth = waiting(2); // 还有一问在答，第四问仍在队首。
        assertFalse(fourth.started.isDone());
        second.complete();
        fourth.expectStarted();
        assertFalse(fifth.started.isDone());
        fourth.complete();
        fifth.expectStarted();
        fifth.complete();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void ninetySecondsExpiresWithOrWithoutARelease(boolean releaseAtDeadline) throws Exception {
        settings.setMaxConcurrentQuestions(1);
        var first = running();
        var expired = waiting(1);
        nanos.addAndGet(SECONDS.toNanos(60));
        var next = waiting(2);
        nanos.addAndGet(SECONDS.toNanos(30));
        if (releaseAtDeadline) first.complete();
        var error = assertThrows(ExecutionException.class, () -> expired.result.get(10, SECONDS));
        assertEquals("现在提问的人比较多，请稍后再问", error.getCause().getMessage());
        assertFalse(expired.started.isDone());
        if (!releaseAtDeadline) first.complete();
        next.expectStarted();
        next.complete();
    }

    @Test void interruptionAndShutdownReleaseWaitingCallersWithoutStartingThem() throws Exception {
        settings.setMaxConcurrentQuestions(1);
        var first = running();
        var interrupted = waiting(1);
        var next = waiting(2);
        interrupted.result.cancel(true);
        first.complete();
        next.expectStarted();
        assertFalse(interrupted.started.isDone());
        var closing = waiting(1);
        queue.close();
        var error = assertThrows(ExecutionException.class, () -> closing.result.get(10, SECONDS));
        assertInstanceOf(AssistantException.class, error.getCause());
        assertFalse(closing.started.isDone());
        next.complete();
    }
}
