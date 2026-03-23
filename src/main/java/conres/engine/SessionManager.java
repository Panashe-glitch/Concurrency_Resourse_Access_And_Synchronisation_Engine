package conres.engine;

import conres.interfaces.IAccessCoordinator;
import conres.interfaces.IAdmissionController;
import conres.model.DemoConfig;
import conres.model.UserID;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Manages the lifecycle of user sessions.
 *
 * Each session is a UserSession with its own single-thread executor (FR16).
 * Logout cleanup (C6 try-finally equivalent):
 *   1. session.shutdown() -- interrupts in-progress operations (Bug #1 fix)
 *   2. forceRelease any held RW lock tracking (accessCoordinator.forceRelease)
 *   3. Remove from session tracking
 *   4. Release semaphore permit (admissionController.release)
 *
 * The critical insight: session.shutdown() interrupts the session thread,
 * which triggers finally blocks in ResourceAccessManager to release locks
 * from the OWNING thread. forceRelease() then cleans up tracking state.
 * This eliminates the wrong-thread-unlock bug entirely.
 *
 * Satisfies: FR4, FR5, FR6, FR11, FR16 (via UserSession).
 */
public class SessionManager {

    private final ConcurrentHashMap<String, UserSession> activeSessions = new ConcurrentHashMap<>();
    private final IAdmissionController admissionController;
    private final IAccessCoordinator accessCoordinator;
    private final ResourceAccessManager resourceAccessManager;
    private volatile Consumer<String> eventListener = msg -> {};
    private volatile MetricsCollector metrics;
    private ScheduledExecutorService watchdog;

    public SessionManager(IAdmissionController admissionController,
                          IAccessCoordinator accessCoordinator,
                          ResourceAccessManager resourceAccessManager) {
        this.admissionController = admissionController;
        this.accessCoordinator = accessCoordinator;
        this.resourceAccessManager = resourceAccessManager;
    }

    public void setEventListener(Consumer<String> listener) {
        this.eventListener = listener;
    }

    public void setMetrics(MetricsCollector metrics) {
        this.metrics = metrics;
    }

    /** Starts the inactivity timeout watchdog. Converts L1 assumption A3 into enforcement. */
    public void startTimeoutWatchdog() {
        watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Session-Timeout-Watchdog");
            t.setDaemon(true);
            return t;
        });
        watchdog.scheduleAtFixedRate(this::checkTimeouts, 10, 5, TimeUnit.SECONDS);
    }

    private void checkTimeouts() {
        long timeoutMs = DemoConfig.SESSION_TIMEOUT_MS;
        for (UserSession session : activeSessions.values()) {
            if (session.getIdleMs() > timeoutMs) {
                UserID uid = session.getUserID();
                eventListener.accept("[TIMEOUT] " + uid + " inactive for " +
                        (session.getIdleMs() / 1000) + "s - forcing logout");
                try {
                    if (metrics != null) {
                        metrics.setThreadState(uid.getId(),
                                MetricsCollector.JavaThreadState.TERMINATED,
                                MetricsCollector.CriticalPhase.NONE,
                                "Timeout: inactive " + (session.getIdleMs() / 1000) + "s");
                    }
                    logout(uid);
                    if (metrics != null) {
                        metrics.recordLogout();
                    }
                } catch (Exception e) {
                    // Best effort - don't crash watchdog
                }
            }
        }
    }

    /**
     * Creates a session with a dedicated thread for the admitted user (FR16).
     */
    public UserSession createSession(UserID userID) {
        UserSession session = new UserSession(userID);
        activeSessions.put(userID.getId(), session);
        return session;
    }

    /** Checks if a user has an active session. */
    public boolean hasSession(UserID userID) {
        return activeSessions.containsKey(userID.getId());
    }

    /** Gets the UserSession for an active session by ID. Returns null if not found. */
    public UserSession getSession(String id) {
        return activeSessions.get(id);
    }

    /** Gets the UserSession by username (case-insensitive search). For CLI support. */
    public UserSession getSessionByUsername(String username) {
        for (UserSession s : activeSessions.values()) {
            if (s.getUserID().getUsername().equalsIgnoreCase(username)) return s;
        }
        return null;
    }

    /**
     * Logs out a user -- cleanup in correct order (C6).
     *
     * Step 1: Shut down session executor (interrupts in-progress ops).
     *         The session thread's finally blocks release any held locks
     *         from the owning thread (fixes Bug #1).
     * Step 2: Clean up tracking state in access coordinator (safety net).
     * Step 3: Remove from session tracking.
     * Step 4: Return semaphore permit (S8 conservation).
     */
    public void logout(UserID userID) {
        UserSession session = activeSessions.get(userID.getId());
        try {
            if (session != null) {
                session.shutdown();                     // Step 1: interrupt + await
            }
            accessCoordinator.forceRelease(userID);     // Step 2: tracking cleanup
        } finally {
            activeSessions.remove(userID.getId());      // Step 3: untrack
            admissionController.release(userID);        // Step 4: return permit
        }
    }

    /** Shuts down all active sessions and the timeout watchdog. */
    public void shutdown() {
        if (watchdog != null) watchdog.shutdownNow();
        for (UserSession session : activeSessions.values()) {
            try {
                logout(session.getUserID());
            } catch (Exception ignored) {}
        }
    }

    public ResourceAccessManager getResourceAccessManager() {
        return resourceAccessManager;
    }

    public int getActiveSessionCount() {
        return activeSessions.size();
    }
}
