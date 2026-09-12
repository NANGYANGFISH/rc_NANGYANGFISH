package notification;

import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** A single bounded worker: an unavailable supplier does not block request acceptance. */
public final class DeliveryWorker implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(DeliveryWorker.class.getName());
    private final NotificationStore store;
    private final HttpDelivery delivery;
    private final int maxAttempts;
    private final long baseDelay;
    private final long maxDelay;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean healthy = true;
    private Notification.Job pendingJob;
    private HttpDelivery.Result pendingResult;

    public DeliveryWorker(NotificationStore store, HttpDelivery delivery, int maxAttempts, long baseDelay, long maxDelay) {
        if (maxAttempts < 1 || maxAttempts > 100 || baseDelay < 1 || maxDelay < baseDelay || maxDelay > 86400000) {
            throw new IllegalArgumentException("Invalid retry configuration");
        }
        this.store = store;
        this.delivery = delivery;
        this.maxAttempts = maxAttempts;
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
    }

    public void start() {
        executor.scheduleWithFixedDelay(() -> {
            try {
                runOnce(System.currentTimeMillis());
                healthy = true;
            } catch (Exception e) {
                healthy = false;
                LOG.warning("Notification worker unavailable: " + e.getClass().getSimpleName());
            }
        }, 0, 200, TimeUnit.MILLISECONDS);
    }

    synchronized boolean runOnce(long now) throws Exception {
        // Retain the result if a DB write fails: retry recording it before making another HTTP call.
        if (pendingJob == null) {
            pendingJob = store.claim(now);
            if (pendingJob == null) {
                return false;
            }
            if (pendingJob.attempts > maxAttempts) {
                pendingResult = new HttpDelivery.Result(null, false, "ATTEMPT_BUDGET_EXHAUSTED", 0);
            } else {
                try {
                    pendingResult = delivery.send(pendingJob.request);
                } catch (RuntimeException e) {
                    pendingResult = new HttpDelivery.Result(null, true, "DELIVERY_ERROR", 0);
                }
            }
        }
        String state = pendingResult.success() ? "SUCCEEDED"
                : pendingResult.retryable && pendingJob.attempts < maxAttempts ? "RETRY" : "DEAD";
        long completedAt = Math.max(now, System.currentTimeMillis());
        long next = "RETRY".equals(state) ? completedAt + Math.max(backoff(pendingJob.attempts), pendingResult.retryAfter) : 0;
        store.finish(pendingJob.request.id, state, next, pendingResult.status, pendingResult.error, completedAt);
        LOG.info("notification=" + pendingJob.request.id + " state=" + state + " attempts=" + pendingJob.attempts);
        pendingJob = null;
        pendingResult = null;
        return true;
    }

    long backoff(int attempts) {
        long ceiling = baseDelay;
        for (int i = 1; i < attempts && ceiling < maxDelay; i++) {
            ceiling = Math.min(maxDelay, ceiling * 2);
        }
        // Equal jitter: [ceiling/2, ceiling], bounded and nonzero.
        return ThreadLocalRandom.current().nextLong(Math.max(1, ceiling / 2), ceiling + 1);
    }

    public boolean healthy() {
        return healthy && !executor.isShutdown();
    }

    @Override
    public void close() throws Exception {
        executor.shutdown();
        if (!executor.awaitTermination(12, TimeUnit.SECONDS)) {
            delivery.close();
            executor.shutdownNow();
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                throw new SQLException("Worker did not stop; database remains open until process exit");
            }
        }
        delivery.close();
    }
}