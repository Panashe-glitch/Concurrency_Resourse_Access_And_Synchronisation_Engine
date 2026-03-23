package conres.engine;

import conres.interfaces.IAccessCoordinator;
import conres.model.UserID;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

/**
 * Readers-writer coordination using a fair ReentrantReadWriteLock (C2).
 *
 * P-invariant (S2): WriterActive + WriterIdle = 1        (TINA verified)
 * P-invariant (S3): !(WriterActive AND ReadersActive > 0)  (TINA verified)
 * S4: currentReaders.size() >= 0                          (structural)
 * S5: At most one writer at any time                      (corollary of S2)
 *
 * C9 ordering: This lock is acquired AFTER semaphore permit (Semaphore -> RW Lock -> File IO).
 *              Eliminates circular wait -> L3 (deadlock freedom).
 * L2: Fair lock FIFO prevents writer starvation (NFR3).
 * L4: Fair lock FIFO prevents reader starvation (NFR3, NFR4).
 *
 * Lock-during-delay design note (NFR5 tension):
 *   Demo delays (Thread.sleep) execute INSIDE the lock boundary intentionally.
 *   This holds the lock for 5 seconds so the assessor can type STATUS and
 *   observe "Reading..." or "Updating..." in the display. Without this, the
 *   lock is held for only a few milliseconds of file IO, and the assessor
 *   could never observe the concurrent state. The throughput cost is accepted
 *   for demo visibility. Phase 7 stress tests set DEMO_MODE=false (zero delays)
 *   to measure true lock contention.
 *
 * Satisfies: FR7 (concurrent reads), FR8 (exclusive write), FR9, FR10, FR11.
 */
public class LocalAccessCoordinator implements IAccessCoordinator {

    private final ReentrantReadWriteLock rwLock;                                 // C2: fair=true
    private final ReentrantReadWriteLock.ReadLock readLock;
    private final ReentrantReadWriteLock.WriteLock writeLock;
    private final Set<UserID> currentReaders = ConcurrentHashMap.newKeySet();    // Thread-safe tracking
    private final AtomicReference<UserID> currentWriter = new AtomicReference<>(null);
    private final AtomicInteger versionCounter = new AtomicInteger(0);

    public LocalAccessCoordinator() {
        this(true);                                                             // default: fair=true
    }

    /** Parameterised constructor for fair vs non-fair comparison (Phase 7 testing). */
    public LocalAccessCoordinator(boolean fair) {
        this.rwLock = new ReentrantReadWriteLock(fair);                          // fair -> FIFO
        this.readLock = rwLock.readLock();
        this.writeLock = rwLock.writeLock();
    }

    /**
     * Acquires read lock (interruptible). Multiple readers may hold simultaneously (FR7, S3).
     * Blocks if a writer holds the write lock.
     * Uses lockInterruptibly() so session threads can be cleanly interrupted
     * during logout -- the interrupt triggers InterruptedException, preventing
     * lock acquisition, so no lock is held and no cleanup is needed.
     *
     * @throws InterruptedException if interrupted while waiting for lock
     */
    @Override
    public void beginRead(UserID userID) throws InterruptedException {
        readLock.lockInterruptibly();              // Responds to Thread.interrupt()
        currentReaders.add(userID);                // Track for snapshot (FR14)
    }

    /**
     * Releases read lock. Always called in try-finally.
     */
    @Override
    public void endRead(UserID userID) {
        currentReaders.remove(userID);
        readLock.unlock();
    }

    /**
     * Acquires exclusive write lock (interruptible). Only one writer at a time (S2, FR8).
     * Blocks if any readers or another writer hold locks (S3).
     *
     * @throws InterruptedException if interrupted while waiting for lock
     */
    @Override
    public void beginWrite(UserID userID) throws InterruptedException {
        writeLock.lockInterruptibly();          // Responds to Thread.interrupt()
        currentWriter.set(userID);              // Track for snapshot (FR15)
    }

    /**
     * Releases write lock. Does NOT increment version -- caller must
     * explicitly call incrementVersion() only after a successful write.
     * This prevents phantom version increments on failed writes (Bug #3 fix).
     * Always called in try-finally.
     */
    @Override
    public void endWrite(UserID userID) {
        currentWriter.set(null);
        writeLock.unlock();
    }

    /**
     * Increments the file version counter. Called by ResourceAccessManager
     * ONLY after a successful write, BEFORE endWrite releases the lock.
     * This ensures the version counter reflects actual successful mutations.
     */
    @Override
    public void incrementVersion() {
        versionCounter.incrementAndGet();
    }

    /**
     * Cleans up tracking state for a user during logout/exception cleanup (C6).
     *
     * Design: This method only removes the user from currentReaders/currentWriter
     * tracking sets. It does NOT attempt to unlock the ReentrantReadWriteLock
     * because RRWL requires release from the owning thread (the session thread).
     *
     * The actual lock release happens via the session thread's finally blocks:
     *   1. UserSession.shutdown() calls executor.shutdownNow() (sends interrupt)
     *   2. Session thread's Thread.sleep() throws InterruptedException
     *   3. finally block in ResourceAccessManager calls endRead/endWrite
     *   4. Lock is properly released by the owning thread
     *
     * If the session thread is blocked on lockInterruptibly(), the interrupt
     * causes InterruptedException before the lock is acquired, so no lock
     * is held and no release is needed.
     */
    @Override
    public void forceRelease(UserID userID) {
        currentReaders.remove(userID);
        currentWriter.compareAndSet(userID, null);
    }

    @Override
    public Set<UserID> getCurrentReaders() {
        return Set.copyOf(currentReaders);  // Defensive copy
    }

    @Override
    public Optional<UserID> getCurrentWriter() {
        return Optional.ofNullable(currentWriter.get());
    }

    @Override
    public int getVersion() {
        return versionCounter.get();
    }
}
