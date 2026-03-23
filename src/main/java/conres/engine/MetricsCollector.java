package conres.engine;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;

/**
 * Collects runtime performance metrics for the concurrency engine.
 * All counters are atomic -- safe to read from dashboard thread while
 * session threads update concurrently.
 *
 * Thread lifecycle tracking (Lecture 2, slide 33):
 *   Maps each user to their current Java thread state
 *   (New, Runnable, Blocked, Waiting, Timed_Waiting, Terminated)
 *   and critical section phase (Lecture 3, slide 19):
 *   (Entry, Critical, Exit, Remainder).
 *
 * CW2 bridge: Engine-layer, transport-agnostic.
 * Satisfies: NFR6 (observability), supports quantitative analysis in report.
 */
public class MetricsCollector {

    private final AtomicLong totalReads = new AtomicLong(0);
    private final AtomicLong totalWrites = new AtomicLong(0);
    private final AtomicLong totalLogins = new AtomicLong(0);
    private final AtomicLong totalLogouts = new AtomicLong(0);
    private final AtomicLong contentionEvents = new AtomicLong(0);
    private final AtomicLong totalLockWaitNanos = new AtomicLong(0);
    private final AtomicInteger peakConcurrentReaders = new AtomicInteger(0);
    private final AtomicInteger peakActiveUsers = new AtomicInteger(0);
    private final long startTimeMs = System.currentTimeMillis();

    private final CopyOnWriteArrayList<OperationRecord> operationHistory = new CopyOnWriteArrayList<>();

    // =========================================================================
    // THREAD LIFECYCLE TRACKING (Lecture 2, slide 33)
    // =========================================================================

    /**
     * Java thread states as taught in Lecture 2.
     * Tracked at application level so the dashboard can display them
     * without JMX or thread inspection.
     */
    public enum JavaThreadState {
        NEW,            // Thread created but not yet started
        RUNNABLE,       // Thread actively executing code
        BLOCKED,        // Waiting for a lock (RW lock contention)
        WAITING,        // Waiting indefinitely (semaphore.acquire)
        TIMED_WAITING,  // Waiting for a fixed time (Thread.sleep)
        TERMINATED      // Thread has finished execution
    }

    /**
     * Critical section phases (Lecture 3, slide 19).
     * Entry -> Critical -> Exit -> Remainder.
     */
    public enum CriticalPhase {
        NONE,           // Not in any operation
        ENTRY,          // Requesting lock (entry section)
        CRITICAL,       // Inside critical section (performing IO)
        EXIT,           // Releasing lock (exit section)
        REMAINDER       // Post-operation (logging, metrics)
    }

    /** Immutable snapshot of a user's thread state. */
    public record ThreadStateInfo(
            JavaThreadState javaState,
            CriticalPhase phase,
            String detail,
            long timestampMs
    ) {
        public String toJson() {
            return String.format(
                "{\"state\":\"%s\",\"phase\":\"%s\",\"detail\":\"%s\",\"ts\":%d}",
                javaState.name(), phase.name(), escapeJson(detail), timestampMs);
        }
        private static String escapeJson(String s) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    /** Per-user thread state. Thread-safe via ConcurrentHashMap. */
    private final ConcurrentHashMap<String, ThreadStateInfo> threadStates = new ConcurrentHashMap<>();

    /** Records a thread state transition for a user. */
    public void setThreadState(String userId, JavaThreadState state,
                               CriticalPhase phase, String detail) {
        threadStates.put(userId, new ThreadStateInfo(
                state, phase, detail, System.currentTimeMillis()));
    }

    /** Returns a snapshot of all tracked thread states. */
    public Map<String, ThreadStateInfo> getThreadStates() {
        return Map.copyOf(threadStates);
    }

    /** Removes a user's thread state (e.g. after prolonged termination). */
    public void clearThreadState(String userId) {
        threadStates.remove(userId);
    }

    /** Serialises thread states to JSON object. */
    public String threadStatesToJson() {
        Map<String, ThreadStateInfo> snapshot = getThreadStates();
        if (snapshot.isEmpty()) return "{}";
        return "{" + snapshot.entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\":" + e.getValue().toJson())
                .collect(Collectors.joining(",")) + "}";
    }

    // =========================================================================
    // OPERATION HISTORY
    // =========================================================================

    public record OperationRecord(
            long timestampMs, String userId, String type,
            long lockWaitMs, long totalDurationMs, boolean contention
    ) {
        public String toJson() {
            return String.format(
                "{\"ts\":%d,\"user\":\"%s\",\"type\":\"%s\",\"lockWaitMs\":%d," +
                "\"durationMs\":%d,\"contention\":%b}",
                timestampMs, userId, type, lockWaitMs, totalDurationMs, contention);
        }
    }

    // =========================================================================
    // RECORDING METHODS
    // =========================================================================

    public void recordRead() { totalReads.incrementAndGet(); }
    public void recordWrite() { totalWrites.incrementAndGet(); }
    public void recordLogin() { totalLogins.incrementAndGet(); }
    public void recordLogout() { totalLogouts.incrementAndGet(); }

    public void recordLockWait(long waitNanos) {
        totalLockWaitNanos.addAndGet(waitNanos);
        if (waitNanos > 1_000_000) {
            contentionEvents.incrementAndGet();
        }
    }

    public void recordOperation(String userId, String type,
                                long lockWaitNanos, long totalDurationNanos) {
        long lockWaitMs = lockWaitNanos / 1_000_000;
        long totalMs = totalDurationNanos / 1_000_000;
        OperationRecord rec = new OperationRecord(
                System.currentTimeMillis(), userId, type,
                lockWaitMs, totalMs, lockWaitMs > 1);
        operationHistory.add(rec);
        while (operationHistory.size() > 50) {
            operationHistory.remove(0);
        }
    }

    public void updatePeakReaders(int current) {
        peakConcurrentReaders.updateAndGet(prev -> Math.max(prev, current));
    }

    public void updatePeakActiveUsers(int current) {
        peakActiveUsers.updateAndGet(prev -> Math.max(prev, current));
    }

    // =========================================================================
    // QUERY METHODS
    // =========================================================================

    public long getTotalReads() { return totalReads.get(); }
    public long getTotalWrites() { return totalWrites.get(); }
    public long getTotalLogins() { return totalLogins.get(); }
    public long getTotalLogouts() { return totalLogouts.get(); }
    public long getContentionEvents() { return contentionEvents.get(); }
    public int getPeakConcurrentReaders() { return peakConcurrentReaders.get(); }
    public int getPeakActiveUsers() { return peakActiveUsers.get(); }

    public long getUptimeSeconds() {
        return (System.currentTimeMillis() - startTimeMs) / 1000;
    }

    public double getAverageLockWaitMs() {
        long totalOps = totalReads.get() + totalWrites.get();
        if (totalOps == 0) return 0.0;
        return (totalLockWaitNanos.get() / 1_000_000.0) / totalOps;
    }

    public double getOpsPerSecond() {
        long uptimeSec = getUptimeSeconds();
        if (uptimeSec == 0) return 0.0;
        return (double) (totalReads.get() + totalWrites.get()) / uptimeSec;
    }

    public String metricsToJson() {
        return String.format(
            "{\"totalReads\":%d,\"totalWrites\":%d,\"totalLogins\":%d," +
            "\"totalLogouts\":%d,\"contentionEvents\":%d," +
            "\"peakConcurrentReaders\":%d,\"peakActiveUsers\":%d," +
            "\"avgLockWaitMs\":%.2f,\"opsPerSecond\":%.2f,\"uptimeSeconds\":%d}",
            totalReads.get(), totalWrites.get(), totalLogins.get(),
            totalLogouts.get(), contentionEvents.get(),
            peakConcurrentReaders.get(), peakActiveUsers.get(),
            getAverageLockWaitMs(), getOpsPerSecond(), getUptimeSeconds()
        );
    }

    public String toJson() { return metricsToJson(); }

    public String operationHistoryToJson() {
        List<OperationRecord> snapshot = List.copyOf(operationHistory);
        return "[" + snapshot.stream().map(OperationRecord::toJson)
                .collect(Collectors.joining(",")) + "]";
    }
}
