package com.store.inventory.support;

import com.store.inventory.application.notification.RetryScheduler;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.RejectedExecutionException;

public final class ManualRetryScheduler implements RetryScheduler {

    private final MutableClock clock;
    private final PriorityQueue<Job> jobs = new PriorityQueue<>(Comparator.comparing(job -> job.due));
    private final List<Duration> delays = new ArrayList<>();
    private boolean closed;

    public ManualRetryScheduler(MutableClock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized Cancellable schedule(Duration delay, Runnable task) {
        if (closed) {
            throw new RejectedExecutionException("Manual scheduler is closed");
        }
        var job = new Job(clock.instant().plus(delay), task);
        delays.add(delay);
        jobs.add(job);
        return () -> job.cancelled = true;
    }

    public boolean runNext() {
        Job job;
        synchronized (this) {
            do {
                job = jobs.poll();
            } while (job != null && job.cancelled);
        }
        if (job == null) {
            return false;
        }
        var remaining = Duration.between(clock.instant(), job.due);
        if (!remaining.isNegative()) {
            clock.advance(remaining);
        }
        job.task.run();
        return true;
    }

    public int runAll() {
        int count = 0;
        while (runNext()) {
            if (++count > 100) {
                throw new IllegalStateException("Unexpected endless retries");
            }
        }
        return count;
    }

    public synchronized List<Duration> delays() {
        return List.copyOf(delays);
    }

    public synchronized long pendingCount() {
        return jobs.stream().filter(job -> !job.cancelled).count();
    }

    @Override
    public synchronized void close() {
        closed = true;
        jobs.clear();
    }

    private static final class Job {
        private final Instant due;
        private final Runnable task;
        private volatile boolean cancelled;

        private Job(Instant due, Runnable task) {
            this.due = due;
            this.task = task;
        }
    }
}
