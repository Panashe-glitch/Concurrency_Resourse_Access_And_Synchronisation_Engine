package conres.engine;

import conres.model.UserID;

import java.util.concurrent.*;

/**
 * Represents a single user session backed by a dedicated thread (FR16).
 *
 * Each UserSession owns a single-thread ExecutorService. All READ/WRITE
 * operations for this user are submitted to this executor, guaranteeing:
 *   - FR16: "Each user session runs as a separate thread"
 *   - Sequential operation ordering per user (no concurrent self-contention)
 *   - Clean shutdown via executor.shutdownNow() + interrupt propagation
 *
 * Shutdown sequence (fixes Bug #1 -- forceRelease wrong-thread unlock):
 *   1. executor.shutdownNow() sends interrupt to the session thread
 *   2. If thread is in Thread.sleep() -> InterruptedException -> finally releases lock
 *   3. If thread is in lockInterruptibly() -> InterruptedException -> no lock held
 *   4. If thread is in file IO -> interrupt flag set, IO completes, finally releases lock
 *   5. awaitTermination() waits for cleanup to complete
 *   Result: lock is ALWAYS released by the owning thread, never from a foreign thread.
 *
 * This replaces the raw Thread creation in CommandDispatcher (Bug #8 fix)
 * and daemon threads that died on JVM exit (Bug #14 fix).
 *
 * Satisfies: FR16 (per-session thread), C6 (cleanup via interrupt + finally).
 */
public class UserSession {

    private final UserID userID;
    private final ExecutorService executor;
    private volatile long lastActivity;  // Updated on every operation; checked by timeout watchdog

    public UserSession(UserID userID) {
        this.userID = userID;
        this.lastActivity = System.currentTimeMillis();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Session-" + userID.getId());
            // NOT daemon: proper shutdown ensures thread cleanup
            return t;
        });
    }

    public UserID getUserID() {
        return userID;
    }

    /** Update last activity timestamp. Called before every READ/WRITE operation. */
    public void touchActivity() {
        this.lastActivity = System.currentTimeMillis();
    }

    /** Returns ms since last activity. Used by timeout watchdog. */
    public long getIdleMs() {
        return System.currentTimeMillis() - lastActivity;
    }

    /**
     * Submits an operation (READ/WRITE) to this session's thread.
     * Operations queue behind any in-progress operation for this user.
     */
    public void submit(Runnable task) {
        executor.submit(task);
    }

    /**
     * Shuts down this session's thread, interrupting any in-progress operation.
     * The interrupt triggers InterruptedException in Thread.sleep() or
     * lockInterruptibly(), causing the finally block to release any held lock
     * from the correct (owning) thread.
     *
     * Waits up to 3 seconds for the thread to finish cleanup.
     */
    public void shutdown() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
