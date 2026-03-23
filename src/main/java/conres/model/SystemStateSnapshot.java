package conres.model;

import java.util.List;
import java.util.Optional;

/**
 * Immutable snapshot of the entire system state at a point in time.
 * Consumed exclusively by SystemDisplay (presentation layer).
 * Never exposes concurrent collections directly -- all fields are defensive copies.
 * 
 * Enforces: NFR6 (observability), NFR7 (separation of concerns).
 * Satisfies: FR12 (active users), FR13 (waiting users), FR14/FR15 (file status).
 */
public final class SystemStateSnapshot {
    private final List<UserID> activeUserIDs;
    private final List<UserID> waitingUserIDs;
    private final List<UserID> currentReaders;
    private final Optional<UserID> currentWriter;
    private final String resourceId;
    private final FileStatus fileStatus;
    private final int versionCounter;
    private final int availablePermits;

    public SystemStateSnapshot(
            List<UserID> activeUserIDs,
            List<UserID> waitingUserIDs,
            List<UserID> currentReaders,
            Optional<UserID> currentWriter,
            String resourceId,
            FileStatus fileStatus,
            int versionCounter,
            int availablePermits) {
        // Defensive copies -- immutability guarantee
        this.activeUserIDs = List.copyOf(activeUserIDs);
        this.waitingUserIDs = List.copyOf(waitingUserIDs);
        this.currentReaders = List.copyOf(currentReaders);
        this.currentWriter = currentWriter;
        this.resourceId = resourceId;
        this.fileStatus = fileStatus;
        this.versionCounter = versionCounter;
        this.availablePermits = availablePermits;
    }

    public List<UserID> getActiveUserIDs() { return activeUserIDs; }
    public List<UserID> getWaitingUserIDs() { return waitingUserIDs; }
    public List<UserID> getCurrentReaders() { return currentReaders; }
    public Optional<UserID> getCurrentWriter() { return currentWriter; }
    public String getResourceId() { return resourceId; }
    public FileStatus getFileStatus() { return fileStatus; }
    public int getVersionCounter() { return versionCounter; }
    public int getAvailablePermits() { return availablePermits; }
}
