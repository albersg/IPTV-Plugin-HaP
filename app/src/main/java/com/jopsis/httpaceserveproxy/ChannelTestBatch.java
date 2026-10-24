package com.jopsis.httpaceserveproxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Runs a batch of channel peer checks concurrently, with a bound, and aborts every one of them
 * when it is cancelled.
 *
 * <p>Plain Java on purpose: it holds the scheduling rules for "testeo de canales rapido,
 * asincrono, paralelo y cancelable" and is pinned by JVM tests
 * ({@code ChannelTestBatchTest}). It knows nothing about {@link HapBridge} or Android; the caller
 * supplies the check, the handles and the playback signal.
 *
 * <p>The rules it enforces:
 * <ul>
 *   <li>at most {@code parallelism} checks in flight, and never an unbounded number of them;</li>
 *   <li>while a playback is active, at most {@link #PLAYBACK_PARALLELISM} check in flight, and the
 *       batch widens again as soon as playback ends;</li>
 *   <li>a channel in the batch is checked exactly once (duplicates are dropped);</li>
 *   <li>cancel aborts every in-flight check through its own handle, and a cancelled check is
 *       reported as neither a result nor a failure;</li>
 *   <li>a failing check records its own outcome and the batch keeps going.</li>
 * </ul>
 *
 * <p>{@link Listener} callbacks run on the worker threads that run the checks.
 */
public final class ChannelTestBatch<R> {

    /** Floor: a batch stays usable, sequential, on a one or two core box. */
    private static final int MIN_PARALLELISM = 1;
    /** Ceiling: a big-core box must not open more sessions than playback can afford. */
    private static final int MAX_PARALLELISM = 3;
    /** Concurrency allowed while something is actually being watched (S3: playback wins). */
    public static final int PLAYBACK_PARALLELISM = 1;
    /** How often the driver re-reads the playback signal and the in-flight count. */
    private static final long POLL_INTERVAL_MS = 250L;

    /**
     * Concurrency for an idle box, derived from the CPU count.
     *
     * <p>The reference device is an Amlogic S905X5M: 4 cores, 1.96 GB RAM with roughly 700-850 MB
     * free, armeabi-v7a, Google TV. An in-flight check is not just a socket: it holds a live P2P
     * session and a control connection on the AceServe engine, which is also what feeds the
     * stream, and the proxy fans that stream out on its own threads. So the batch is held to half
     * the cores - 2 concurrent checks on that device - which leaves the rest for the streaming
     * path, and it stays I/O bound well below the core count anyway.
     *
     * <p>The range is [1, 3]. The floor keeps the batch working on a single-core box instead of
     * stalling or going unbounded. The ceiling matters because the proxy only allows
     * {@code Config::max_concurrent_channels} = 5 concurrent channel sessions; 3 checks leave 2
     * slots for playback, which is the documented trade-off (playback first, tests second). More
     * checks than that would also multiply per-session memory on a 1.96 GB device.
     */
    public static int parallelLimit(int cores) {
        return Math.max(MIN_PARALLELISM, Math.min(MAX_PARALLELISM, cores / 2));
    }

    /** One cancellable check. Must treat {@link Cancellation#isCancelled()} as an abort. */
    public interface Check<R> {
        R run(String contentId, Cancellation cancellation) throws Exception;
    }

    /**
     * Handle for aborting one in-flight check.
     *
     * <p>The batch keeps one per running check and cancels all of them on {@link #cancel()}, so a
     * stop aborts the whole batch instead of only the check that happens to be current.
     */
    public interface Cancellation {
        boolean isCancelled();

        void cancel();
    }

    /** Batch outcome. Called at most once per finished check, and once for the batch itself. */
    public interface Listener<R> {
        void onResult(String contentId, R result, int completed, int total);

        void onFailure(String contentId, Exception error, int completed, int total);

        void onFinished(boolean cancelled);
    }

    private final List<String> contentIds;
    private final int parallelism;
    private final Supplier<Cancellation> cancellationFactory;
    private final Check<R> check;
    private final ExecutorService workers;
    private final BooleanSupplier playbackActive;
    private final Listener<R> listener;

    private final Object lock = new Object();
    private final Map<String, Cancellation> live = new LinkedHashMap<>();
    private final List<Future<?>> submitted = new ArrayList<>();
    private int inFlight;
    private int completed;
    private volatile boolean cancelled;
    private volatile boolean finished;

    /**
     * @param contentIds          channels to test, in order; duplicates and empty ids are dropped
     * @param parallelism         maximum concurrent checks for an idle box, at least 1
     * @param cancellationFactory creates the per-check handle, so cancel can reach all of them
     * @param check               the blocking work, run on {@code workers}
     * @param workers             pool for the actual checks; it needs room for {@code parallelism}
     * @param playbackActive      queried before every start; true throttles the batch to
     *                            {@link #PLAYBACK_PARALLELISM}
     * @param listener            receives per-channel outcomes and the batch end
     */
    public ChannelTestBatch(
            List<String> contentIds,
            int parallelism,
            Supplier<Cancellation> cancellationFactory,
            Check<R> check,
            ExecutorService workers,
            BooleanSupplier playbackActive,
            Listener<R> listener) {
        if (parallelism < MIN_PARALLELISM) {
            throw new IllegalArgumentException("parallelism must be >= " + MIN_PARALLELISM);
        }
        this.contentIds = uniqueIds(contentIds);
        this.parallelism = parallelism;
        this.cancellationFactory = cancellationFactory;
        this.check = check;
        this.workers = workers;
        this.playbackActive = playbackActive;
        this.listener = listener;
    }

    /** Channels this batch will actually test, after de-duplication. */
    public int total() {
        return contentIds.size();
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public boolean isFinished() {
        return finished;
    }

    /**
     * Drives the batch on the calling thread and blocks until it ends.
     *
     * <p>Call it from a worker thread, never from the UI thread: it waits for the checks it
     * dispatched, re-reading the playback signal on every poll so a playback that starts mid-batch
     * stops any further start.
     */
    public void run() {
        try {
            try {
                drive();
            } catch (InterruptedException interrupted) {
                // An interrupt alone never aborts a blocking native/socket operation (S4): the
                // handles have to be closed, and the batch has to know it was stopped.
                Thread.currentThread().interrupt();
                cancel();
            } catch (RuntimeException error) {
                cancel();
                throw error;
            }
        } finally {
            // Whatever stopped the batch - a cancel, an interrupt, or a pool that is gone - nothing
            // it dispatched may be left running behind it.
            if (isCancelled()) cancel();
            finished = true;
            listener.onFinished(isCancelled());
        }
    }

    /**
     * Aborts every in-flight check. Safe from any thread, any number of times, and it is what
     * makes stop/abort/onDestroy real for the whole batch.
     */
    public void cancel() {
        List<Cancellation> handles;
        List<Future<?>> futures;
        synchronized (lock) {
            cancelled = true;
            handles = new ArrayList<>(live.values());
            futures = new ArrayList<>(submitted);
            live.clear();
            lock.notifyAll();
        }
        for (Cancellation handle : handles) {
            try {
                handle.cancel();
            } catch (RuntimeException ignored) {
                // A broken handle must not stop the others from being aborted.
            }
        }
        for (Future<?> future : futures) future.cancel(true);
    }

    private void drive() throws InterruptedException {
        int next = 0;
        final int total = contentIds.size();
        while (true) {
            // Read outside the lock: the probe may block on a socket, and the workers need the
            // lock to publish their results.
            boolean playing = !cancelled && playbackActive.getAsBoolean();
            synchronized (lock) {
                discardFinished();
                if (cancelled) return;
                int limit = playing ? PLAYBACK_PARALLELISM : parallelism;
                while (!cancelled && inFlight < limit && next < total) {
                    start(contentIds.get(next++));
                }
                if (cancelled || (next >= total && inFlight == 0)) return;
                lock.wait(POLL_INTERVAL_MS);
            }
        }
    }

    /** Dispatches one check. Must be called with {@link #lock} held. */
    private void start(String contentId) {
        Cancellation handle = cancellationFactory.get();
        live.put(contentId, handle);
        inFlight++;
        try {
            submitted.add(workers.submit(() -> execute(contentId, handle)));
        } catch (RejectedExecutionException rejected) {
            // The pool is gone (activity torn down): stop instead of spinning without work.
            live.remove(contentId);
            inFlight--;
            cancelled = true;
        }
    }

    private void execute(String contentId, Cancellation handle) {
        R result = null;
        Exception failure = null;
        try {
            result = check.run(contentId, handle);
        } catch (Exception error) {
            failure = error;
        } catch (Throwable error) {
            // One broken check must never take the whole batch down.
            failure = new IllegalStateException(error);
        }

        boolean report;
        synchronized (lock) {
            // A cancelled check is neither a result nor an error result.
            report = !cancelled && !handle.isCancelled();
        }
        try {
            if (!report) return;
            int done;
            int total;
            synchronized (lock) {
                completed++;
                done = completed;
                total = contentIds.size();
            }
            if (failure == null) {
                listener.onResult(contentId, result, done, total);
            } else {
                listener.onFailure(contentId, failure, done, total);
            }
        } finally {
            // Counted as finished only after the outcome was reported, so the batch cannot
            // announce its end while a result is still on its way.
            synchronized (lock) {
                live.remove(contentId);
                inFlight--;
                lock.notifyAll();
            }
        }
    }

    /** Must be called with {@link #lock} held. */
    private void discardFinished() {
        for (Iterator<Future<?>> iterator = submitted.iterator(); iterator.hasNext(); ) {
            if (iterator.next().isDone()) iterator.remove();
        }
    }

    private static List<String> uniqueIds(List<String> contentIds) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>(contentIds.size());
        for (String contentId : contentIds) {
            if (contentId == null || contentId.isEmpty()) continue;
            if (seen.add(contentId)) out.add(contentId);
        }
        return Collections.unmodifiableList(out);
    }
}
