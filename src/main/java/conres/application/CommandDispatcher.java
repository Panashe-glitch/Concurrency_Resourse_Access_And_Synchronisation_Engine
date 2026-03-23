package conres.application;

import conres.engine.ResourceAccessManager;
import conres.engine.UserSession;
import conres.model.DemoConfig;
import conres.model.Result;
import conres.model.UserID;
import conres.presentation.SystemDisplay;

/**
 * Dispatches READ/WRITE commands to the user's session thread (FR16).
 *
 * Each command is submitted to the UserSession's single-thread executor
 * rather than spawning a new Thread (Bug #8 fix: bounded thread usage).
 * Operations queue sequentially per user and are interruptible on logout.
 *
 * The session thread is the lock-owning thread, so finally blocks in
 * ResourceAccessManager release locks from the correct thread (Bug #1 fix).
 * Session threads are NOT daemon threads and are properly shut down
 * via UserSession.shutdown() on logout (Bug #14 fix).
 *
 * Satisfies: FR7 (read), FR8 (write), FR16 (per-session thread).
 */
public class CommandDispatcher {

    private final ResourceAccessManager resourceAccessManager;
    private final SystemDisplay systemDisplay;

    public CommandDispatcher(ResourceAccessManager resourceAccessManager,
                             SystemDisplay systemDisplay) {
        this.resourceAccessManager = resourceAccessManager;
        this.systemDisplay = systemDisplay;
    }

    /**
     * Submits a read operation to the user's session thread.
     * Acquires read lock, reads file (with demo delay), releases lock.
     * Handles InterruptedException from lockInterruptibly() (logout during wait).
     */
    public void executeRead(UserSession session) {
        UserID userID = session.getUserID();
        session.submit(() -> {
            try {
                String contents = resourceAccessManager.read(userID, DemoConfig.RESOURCE_ID, session);
                System.out.printf("%n[%s] === File Contents ===%n%s%n[%s] === End ===%n%n",
                        userID, contents, userID);
                systemDisplay.logEvent(userID + " READ complete");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                systemDisplay.logEvent(userID + " READ interrupted (session ending)");
            } catch (Exception e) {
                systemDisplay.logEvent(userID + " READ failed: " + e.getMessage());
            }
        });
    }

    /**
     * Submits a write operation to the user's session thread.
     * Acquires exclusive write lock, writes data (with demo delay), releases lock.
     * Version counter incremented only on success (Bug #3 fix).
     */
    public void executeWrite(UserSession session, String data) {
        UserID userID = session.getUserID();
        session.submit(() -> {
            try {
                Result<Void> result = resourceAccessManager.write(
                        userID, DemoConfig.RESOURCE_ID, data, session);
                if (result.isSuccess()) {
                    systemDisplay.logEvent(userID + " WRITE complete");
                } else {
                    systemDisplay.logEvent(userID + " WRITE failed: " +
                            result.getError().map(Exception::getMessage).orElse("unknown"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                systemDisplay.logEvent(userID + " WRITE interrupted (session ending)");
            }
        });
    }
}
