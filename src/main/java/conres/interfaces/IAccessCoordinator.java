package conres.interfaces;

import conres.model.UserID;
import java.util.Optional;
import java.util.Set;

/**
 * Readers-writer coordination boundary -- RW lock abstraction.
 * CW1: LocalAccessCoordinator (local ReentrantReadWriteLock).
 * CW2: DistributedAccessCoordinator (distributed lock coordination).
 *
 * beginRead/beginWrite throw InterruptedException so that session
 * threads blocked on lock acquisition can be cleanly interrupted
 * during logout (Bug #1 fix: enables forceRelease from owning thread).
 */
public interface IAccessCoordinator {
    void beginRead(UserID userID) throws InterruptedException;
    void endRead(UserID userID);
    void beginWrite(UserID userID) throws InterruptedException;
    void endWrite(UserID userID);
    void incrementVersion();
    void forceRelease(UserID userID);
    Set<UserID> getCurrentReaders();
    Optional<UserID> getCurrentWriter();
    int getVersion();
}
