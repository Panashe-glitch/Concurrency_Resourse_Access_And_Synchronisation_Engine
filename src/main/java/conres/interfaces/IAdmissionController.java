package conres.interfaces;

import conres.model.UserID;
import java.util.List;

/**
 * Admission control boundary -- counting semaphore abstraction.
 * CW1: LocalAdmissionController (local Semaphore).
 * CW2: DistributedAdmissionController (distributed admission service).
 */
public interface IAdmissionController {
    void tryAdmit(UserID userID) throws InterruptedException;
    void release(UserID userID);
    boolean isActive(UserID userID);
    boolean isWaiting(UserID userID);
    List<UserID> getActiveUserIDs();
    List<UserID> getWaitingUserIDs();
    int getAvailablePermits();
}
