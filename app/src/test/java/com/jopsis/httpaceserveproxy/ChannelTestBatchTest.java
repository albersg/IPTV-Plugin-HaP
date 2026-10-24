package com.jopsis.httpaceserveproxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JVM tests for the parallel channel-test batch.
 *
 * <p>The batch is the rule set behind "testeo de canales rapido, asincrono, paralelo y
 * cancelable": how many peer checks run at once, what happens while a playback is active, and
 * what a stop does to every in-flight check. It is plain Java with no android.* imports, so it is
 * pinned here instead of on a device. The reference device is an Amlogic S905X5M (4 cores,
 * 1.96 GB) whose {@code availableProcessors()} is 4.
 */
public final class ChannelTestBatchTest {

    private static final List<String> FIVE = Arrays.asList("a", "b", "c", "d", "e");

    private ExecutorService workers;
    private ExecutorService driver;

    @Before
    public void setUp() {
        workers = Executors.newFixedThreadPool(4, daemon("batch-test-worker"));
        driver = Executors.newSingleThreadExecutor(daemon("batch-test-driver"));
    }

    @After
    public void tearDown() {
        workers.shutdownNow();
        driver.shutdownNow();
    }

    @Test
    public void parallelLimitIsDerivedFromCoreCountAndClamped() {
        // 4 cores on the reference device -> half of them, so the streaming path keeps the rest.
        assertEquals(2, ChannelTestBatch.parallelLimit(4));
        // Never unbounded: a big-core box must not open more sessions than playback can afford.
        assertEquals(3, ChannelTestBatch.parallelLimit(16));
        // Never zero or negative, even on a single-core box.
        assertEquals(1, ChannelTestBatch.parallelLimit(1));
        assertEquals(1, ChannelTestBatch.parallelLimit(2));
        assertEquals(1, ChannelTestBatch.parallelLimit(0));
    }

    @Test
    public void neverRunsMoreChecksAtOnceThanTheLimit() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CountDownLatch twoStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Recorder recorder = new Recorder();

        ChannelTestBatch<String> batch = new ChannelTestBatch<>(
                FIVE,
                2,
                TestCancellation::new,
                (contentId, cancellation) -> {
                    maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                    twoStarted.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    active.decrementAndGet();
                    return contentId;
                },
                workers,
                () -> false,
                recorder);

        runInBackground(batch);
        assertTrue("two checks should run at once", twoStarted.await(5, TimeUnit.SECONDS));
        // Three checks would mean the limit is not enforced.
        Thread.sleep(200);
        assertEquals("limit of 2 must cap the in-flight checks", 2, active.get());
        release.countDown();
        join(batch);

        assertEquals(5, recorder.results.size());
        assertTrue("max concurrency was " + maxActive.get(), maxActive.get() <= 2);
        assertFalse(recorder.cancelled);
    }

    @Test
    public void playbackKeepsTheBatchSequential() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        Recorder recorder = new Recorder();

        ChannelTestBatch<String> batch = new ChannelTestBatch<>(
                FIVE,
                2,
                TestCancellation::new,
                (contentId, cancellation) -> {
                    maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                    Thread.sleep(20);
                    active.decrementAndGet();
                    return contentId;
                },
                workers,
                () -> true,
                recorder);

        runInBackground(batch);
        join(batch);

        assertEquals(5, recorder.results.size());
        assertEquals("playback must throttle the batch to one check", 1, maxActive.get());
    }

    @Test
    public void expandsAgainWhenPlaybackStops() throws Exception {
        AtomicBoolean playbackActive = new AtomicBoolean(true);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        Recorder recorder = new Recorder();

        ChannelTestBatch<String> batch = new ChannelTestBatch<>(
                FIVE,
                2,
                TestCancellation::new,
                (contentId, cancellation) -> {
                    maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                    // Playback ends while the first check runs: the batch must widen again.
                    playbackActive.set(false);
                    Thread.sleep(100);
                    active.decrementAndGet();
                    return contentId;
                },
                workers,
                playbackActive::get,
                recorder);

        runInBackground(batch);
        join(batch);

        assertEquals(5, recorder.results.size());
        assertTrue("batch should widen back to 2, saw " + maxActive.get(), maxActive.get() >= 2);
        assertTrue("batch must not exceed the limit, saw " + maxActive.get(), maxActive.get() <= 2);
    }

    @Test
    public void cancelAbortsEveryInFlightCheckWithoutRecordingThemAsFailures() throws Exception {
        AtomicInteger abortedChecks = new AtomicInteger();
        CountDownLatch twoStarted = new CountDownLatch(2);
        Recorder recorder = new Recorder();

        ChannelTestBatch<String> batch = new ChannelTestBatch<>(
                FIVE,
                2,
                TestCancellation::new,
                (contentId, cancellation) -> {
                    twoStarted.countDown();
                    // Stands in for a blocking socket read: only the handle can stop it.
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!cancellation.isCancelled() && System.nanoTime() < deadline) {
                        Thread.sleep(5);
                    }
                    if (cancellation.isCancelled()) {
                        abortedChecks.incrementAndGet();
                        throw new IOException("channel test cancelled");
                    }
                    return contentId;
                },
                workers,
                () -> false,
                recorder);

        runInBackground(batch);
        assertTrue(twoStarted.await(5, TimeUnit.SECONDS));

        batch.cancel();
        join(batch);

        assertEquals("both in-flight checks must see the cancel", 2, abortedChecks.get());
        assertTrue("a cancelled channel is not a result", recorder.results.isEmpty());
        assertTrue("a cancelled channel is not a failure", recorder.failures.isEmpty());
        assertTrue(recorder.cancelled);
        assertEquals(1, recorder.finishedCount.get());
    }

    @Test
    public void aFailingChannelIsReportedAndTheBatchContinues() throws Exception {
        Recorder recorder = new Recorder();

        ChannelTestBatch<String> batch = new ChannelTestBatch<>(
                Arrays.asList("a", "b", "c"),
                2,
                TestCancellation::new,
                (contentId, cancellation) -> {
                    if ("b".equals(contentId)) throw new IOException("timeout");
                    return contentId;
                },
                workers,
                () -> false,
                recorder);

        runInBackground(batch);
        join(batch);

        assertEquals(Arrays.asList("b"), recorder.failures);
        assertEquals(2, recorder.results.size());
        assertTrue(recorder.results.containsAll(Arrays.asList("a", "c")));
        assertEquals(Arrays.asList(1, 2, 3), recorder.sortedProgress());
        assertFalse(recorder.cancelled);
    }

    @Test
    public void aChannelIsNeverTestedTwiceInTheSameBatch() throws Exception {
        Recorder recorder = new Recorder();
        List<String> checked = Collections.synchronizedList(new ArrayList<>());

        ChannelTestBatch<String> batch = new ChannelTestBatch<>(
                Arrays.asList("a", "a", "b", null, ""),
                2,
                TestCancellation::new,
                (contentId, cancellation) -> {
                    checked.add(contentId);
                    return contentId;
                },
                workers,
                () -> false,
                recorder);

        assertEquals(2, batch.total());
        runInBackground(batch);
        join(batch);

        assertEquals(Arrays.asList("a", "b"), checked);
        assertEquals(2, recorder.results.size());
    }

    private void runInBackground(ChannelTestBatch<String> batch) {
        driver.execute(batch::run);
    }

    private void join(ChannelTestBatch<String> batch) throws InterruptedException {
        // The listener runs on the driver thread, so a finished batch means the run returned.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (batch.isFinished()) return;
            Thread.sleep(5);
        }
        throw new AssertionError("batch did not finish in time");
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Cancel handle shaped like the production one: a flag plus a socket close. */
    private static final class TestCancellation implements ChannelTestBatch.Cancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public void cancel() {
            cancelled.set(true);
        }
    }

    private static final class Recorder implements ChannelTestBatch.Listener<String> {
        final List<String> results = Collections.synchronizedList(new ArrayList<>());
        final List<String> failures = Collections.synchronizedList(new ArrayList<>());
        final List<Integer> progress = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger finishedCount = new AtomicInteger();
        volatile boolean cancelled;

        @Override
        public void onResult(String contentId, String result, int completed, int total) {
            results.add(contentId);
            progress.add(completed);
        }

        @Override
        public void onFailure(String contentId, Exception error, int completed, int total) {
            failures.add(contentId);
            progress.add(completed);
        }

        @Override
        public void onFinished(boolean cancelled) {
            this.cancelled = cancelled;
            finishedCount.incrementAndGet();
        }

        List<Integer> sortedProgress() {
            List<Integer> copy = new ArrayList<>(progress);
            Collections.sort(copy);
            return copy;
        }
    }
}
