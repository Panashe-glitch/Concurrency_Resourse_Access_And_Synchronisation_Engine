package conres.engine;

import conres.interfaces.IAccessCoordinator;
import conres.interfaces.IFileRepository;
import conres.model.DemoConfig;
import conres.model.Result;
import conres.model.UserID;

import java.util.function.Consumer;

import static conres.engine.MetricsCollector.JavaThreadState.*;
import static conres.engine.MetricsCollector.CriticalPhase.*;

/**
 * Orchestrates the beginRead/Write -> file IO -> endRead/Write lifecycle.
 *
 * Thread lifecycle instrumentation (Lecture 2, slide 33):
 *   Every state transition is recorded to MetricsCollector so the dashboard
 *   can display the Java thread state (NEW/RUNNABLE/BLOCKED/TIMED_WAITING/TERMINATED)
 *   in real time.
 *
 * Critical section phases (Lecture 3, slide 19):
 *   Each operation follows Entry -> Critical -> Exit -> Remainder:
 *     ENTRY:     Thread requests lock (beginRead/beginWrite)
 *     CRITICAL:  Thread performs file IO inside the lock
 *     EXIT:      Thread releases lock (endRead/endWrite) in finally block
 *     REMAINDER: Thread logs completion, records metrics
 *
 * Satisfies: FR7, FR8, FR9, FR10, FR11.
 */
public class ResourceAccessManager {

    private final IAccessCoordinator accessCoordinator;
    private final IFileRepository fileRepository;
    private volatile Consumer<String> eventListener;
    private volatile MetricsCollector metrics;

    public ResourceAccessManager(IAccessCoordinator accessCoordinator,
                                 IFileRepository fileRepository) {
        this.accessCoordinator = accessCoordinator;
        this.fileRepository = fileRepository;
        this.eventListener = msg -> {};
        this.metrics = null;
    }

    public void setEventListener(Consumer<String> listener) {
        this.eventListener = listener;
    }

    public void setMetrics(MetricsCollector metrics) {
        this.metrics = metrics;
    }

    private void log(UserID userID, String event) {
        eventListener.accept(userID + " " + event
                + " [" + Thread.currentThread().getName() + "]");
    }

    /**
     * Read operation with full thread lifecycle and critical section tracking.
     *
     * State transitions:
     *   BLOCKED/ENTRY     -> waiting for read lock
     *   RUNNABLE/CRITICAL  -> lock acquired, performing IO
     *   TIMED_WAITING/CRITICAL -> during demo delay (Thread.sleep)
     *   RUNNABLE/EXIT      -> releasing lock
     *   RUNNABLE/REMAINDER -> post-operation metrics
     */
    public String read(UserID userID, String resourceId, UserSession session) throws Exception {
        if (session != null) session.touchActivity();
        long opStart = System.nanoTime();
        String uid = userID.getId();

        // ENTRY SECTION: request lock
        if (metrics != null) metrics.setThreadState(uid, BLOCKED, ENTRY,
                "Waiting for read lock");
        log(userID, "WAITING_FOR_READ_LOCK");
        long waitStart = System.nanoTime();
        accessCoordinator.beginRead(userID);
        long waitNanos = System.nanoTime() - waitStart;
        log(userID, "ACQUIRED_READ_LOCK");

        if (metrics != null) {
            metrics.recordLockWait(waitNanos);
            metrics.updatePeakReaders(accessCoordinator.getCurrentReaders().size());
        }

        try {
            // CRITICAL SECTION: file IO inside the lock
            if (DemoConfig.DEMO_MODE) {
                if (metrics != null) metrics.setThreadState(uid, TIMED_WAITING, CRITICAL,
                        "Reading file (Thread.sleep " + DemoConfig.READ_DELAY_MS + "ms)");
                Thread.sleep(DemoConfig.READ_DELAY_MS);
            }
            if (metrics != null) metrics.setThreadState(uid, RUNNABLE, CRITICAL,
                    "Reading file contents");
            String content = fileRepository.readContents(resourceId);

            if (metrics != null) {
                metrics.recordRead();
                metrics.recordOperation(uid, "READ",
                        waitNanos, System.nanoTime() - opStart);
            }
            return content;
        } finally {
            // EXIT SECTION: release lock (guaranteed by try-finally, C6)
            if (metrics != null) metrics.setThreadState(uid, RUNNABLE, EXIT,
                    "Releasing read lock");
            accessCoordinator.endRead(userID);
            log(userID, "RELEASED_READ_LOCK");

            // REMAINDER SECTION: non-critical post-operation
            if (metrics != null) metrics.setThreadState(uid, RUNNABLE, REMAINDER,
                    "Idle (session active)");
        }
    }

    /**
     * Write operation with full thread lifecycle and critical section tracking.
     *
     * State transitions mirror read() but with write lock semantics.
     * Version counter incremented ONLY on successful write (Bug #3 fix).
     */
    public Result<Void> write(UserID userID, String resourceId, String data, UserSession session)
            throws InterruptedException {
        if (session != null) session.touchActivity();
        long opStart = System.nanoTime();
        String uid = userID.getId();

        // ENTRY SECTION: request exclusive write lock
        if (metrics != null) metrics.setThreadState(uid, BLOCKED, ENTRY,
                "Waiting for write lock (exclusive)");
        log(userID, "WAITING_FOR_WRITE_LOCK");
        long waitStart = System.nanoTime();
        accessCoordinator.beginWrite(userID);
        long waitNanos = System.nanoTime() - waitStart;
        log(userID, "ACQUIRED_WRITE_LOCK");

        if (metrics != null) {
            metrics.recordLockWait(waitNanos);
        }

        try {
            // CRITICAL SECTION: exclusive file IO
            if (DemoConfig.DEMO_MODE) {
                if (metrics != null) metrics.setThreadState(uid, TIMED_WAITING, CRITICAL,
                        "Writing file (Thread.sleep " + DemoConfig.WRITE_DELAY_MS + "ms)");
                Thread.sleep(DemoConfig.WRITE_DELAY_MS);
            }
            if (metrics != null) metrics.setThreadState(uid, RUNNABLE, CRITICAL,
                    "Writing file contents");
            Result<Void> result = fileRepository.writeContents(resourceId, data);
            if (result.isSuccess()) {
                accessCoordinator.incrementVersion();
                if (metrics != null) {
                    metrics.recordWrite();
                    metrics.recordOperation(uid, "WRITE",
                            waitNanos, System.nanoTime() - opStart);
                }
            }
            return result;
        } finally {
            // EXIT SECTION: release write lock (C6 guarantee)
            if (metrics != null) metrics.setThreadState(uid, RUNNABLE, EXIT,
                    "Releasing write lock");
            accessCoordinator.endWrite(userID);
            log(userID, "RELEASED_WRITE_LOCK");

            // REMAINDER SECTION
            if (metrics != null) metrics.setThreadState(uid, RUNNABLE, REMAINDER,
                    "Idle (session active)");
        }
    }

    /** Backward-compatible overload - no session timeout tracking. Used by tests. */
    public String read(UserID userID, String resourceId) throws Exception {
        return read(userID, resourceId, null);
    }

    /** Backward-compatible overload - no session timeout tracking. Used by tests. */
    public Result<Void> write(UserID userID, String resourceId, String data)
            throws InterruptedException {
        return write(userID, resourceId, data, null);
    }
}
