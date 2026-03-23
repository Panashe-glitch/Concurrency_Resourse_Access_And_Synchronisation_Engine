package conres.engine;

import conres.interfaces.IAdmissionController;
import conres.model.UserID;

import java.util.*;
import java.util.concurrent.*;

/**
 * Admission control using a fair counting semaphore (C1) and FIFO waiting queue (C3).
 *
 * P-invariant (S8): availablePermits + activeSet.size() = MAX_CONCURRENT at all times.
 * Proven structurally by TINA (Phase 3, admission_control.net).
 *
 * S1:  activeSet.size() <= MAX_CONCURRENT          (corollary of S8)
 * S6:  semaphore.availablePermits() >= 0            (corollary of S8)
 * S9:  waitingSet INTERSECT activeSet = EMPTY                   (transitionLock atomicity)
 * C8:  Only authenticated users reach tryAdmit()    (SessionController gate)
 * L1:  Fair semaphore FIFO guarantees eventual admission (given A3)
 *
 * Satisfies: FR4, FR5, FR6, FR12, FR13.
 */
public class LocalAdmissionController implements IAdmissionController {

    private final Semaphore semaphore;                                         // C1: fair=true
    private final Set<UserID> activeSet = ConcurrentHashMap.newKeySet();       // Thread-safe set
    private final LinkedBlockingQueue<UserID> waitingQueue = new LinkedBlockingQueue<>(); // C3: FIFO
    private final Object transitionLock = new Object();                        // S9: atomic transitions

    public LocalAdmissionController(int maxConcurrent) {
        this(maxConcurrent, true);                                              // default: fair=true
    }

    /** Parameterised constructor for fair vs non-fair comparison (Phase 7 testing). */
    public LocalAdmissionController(int maxConcurrent, boolean fair) {
        this.semaphore = new Semaphore(maxConcurrent, fair);                    // fair -> FIFO
    }

    /**
     * Attempts to admit a user. Blocks if all permits are held.
     * Waiting -> Active transition is atomic under transitionLock (S9).
     *
     * @throws InterruptedException if thread is interrupted while waiting
     */
    @Override
    public void tryAdmit(UserID userID) throws InterruptedException {
        waitingQueue.add(userID);           // Visible as waiting immediately (FR13)

        semaphore.acquire();                // Blocks if permits=0 (S1, S8)

        // Atomic transition: waiting -> active (S9)
        synchronized (transitionLock) {
            waitingQueue.remove(userID);
            activeSet.add(userID);
        }
    }

    /**
     * Releases a user's permit. Always called in try-finally (C6).
     * S8 conservation: permit returned -> availablePermits incremented.
     */
    @Override
    public void release(UserID userID) {
        synchronized (transitionLock) {
            activeSet.remove(userID);
        }
        semaphore.release();                // C6: always in try-finally
    }

    @Override
    public boolean isActive(UserID userID) {
        return activeSet.contains(userID);
    }

    @Override
    public boolean isWaiting(UserID userID) {
        return waitingQueue.contains(userID);
    }

    @Override
    public List<UserID> getActiveUserIDs() {
        return List.copyOf(activeSet);      // Defensive copy for snapshot (NFR6)
    }

    @Override
    public List<UserID> getWaitingUserIDs() {
        return List.copyOf(waitingQueue);   // Defensive copy preserving FIFO order
    }

    @Override
    public int getAvailablePermits() {
        return semaphore.availablePermits();
    }
}
