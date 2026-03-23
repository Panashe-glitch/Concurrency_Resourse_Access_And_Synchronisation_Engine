package conres.application;

import conres.engine.MetricsCollector;
import conres.interfaces.IAdmissionController;
import conres.interfaces.IAuthenticationService;
import conres.engine.SessionManager;
import conres.model.UserID;
import conres.presentation.SystemDisplay;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static conres.engine.MetricsCollector.JavaThreadState.*;
import static conres.engine.MetricsCollector.CriticalPhase.*;

/**
 * Orchestrates the full login pipeline:
 *   1. Validate credentials (IAuthenticationService) -- on main thread
 *   2. Atomic duplicate + pending check -- on main thread (Bug #2 fix)
 *   3. Admission control (may block on semaphore) -- on BACKGROUND thread
 *   4. Session creation -- on background thread after admission
 *
 * Thread lifecycle tracking (Lecture 2, slide 33):
 *   NEW:     Session object created after authentication
 *   WAITING: Blocked on semaphore.acquire() in admission queue
 *   RUNNABLE: Session thread started after admission
 *
 * Satisfies: FR1, FR2, FR4, FR5, FR6, C8, S9.
 */
public class SessionController {

    private final IAuthenticationService authService;
    private final IAdmissionController admissionController;
    private final SessionManager sessionManager;
    private final SystemDisplay systemDisplay;
    private volatile MetricsCollector metrics;

    private final java.util.Set<String> pendingAdmissions = ConcurrentHashMap.newKeySet();

    public SessionController(IAuthenticationService authService,
                             IAdmissionController admissionController,
                             SessionManager sessionManager,
                             SystemDisplay systemDisplay) {
        this.authService = authService;
        this.admissionController = admissionController;
        this.sessionManager = sessionManager;
        this.systemDisplay = systemDisplay;
    }

    public void setMetrics(MetricsCollector metrics) {
        this.metrics = metrics;
    }

    public boolean login(String username) {
        // Step 1: Authenticate (C8)
        Optional<UserID> maybeUser = authService.validate(username);
        if (maybeUser.isEmpty()) {
            systemDisplay.logEvent("AUTH FAILED: '" + username + "' not in credential store");
            return false;
        }

        UserID userID = maybeUser.get();
        String uid = userID.getId();

        // Step 2: Atomic duplicate check (Bug #2 fix)
        if (admissionController.isActive(userID)
                || admissionController.isWaiting(userID)
                || sessionManager.hasSession(userID)
                || !pendingAdmissions.add(uid)) {
            systemDisplay.logEvent("DUPLICATE REJECTED: '" + userID + "' already active/waiting");
            return false;
        }

        // Thread lifecycle: NEW -- thread object created, not yet started
        if (metrics != null) metrics.setThreadState(uid, NEW, NONE,
                "Authenticated, awaiting admission");

        // Step 3+4: Background admission
        Thread admitThread = new Thread(() -> {
            try {
                // Thread lifecycle: WAITING -- blocked on semaphore.acquire()
                if (metrics != null) metrics.setThreadState(uid, WAITING, NONE,
                        "Blocked on Semaphore.acquire()");
                systemDisplay.logEvent(userID + " requesting admission...");

                admissionController.tryAdmit(userID);       // may block here

                // Thread lifecycle: RUNNABLE -- admitted, creating session
                if (metrics != null) metrics.setThreadState(uid, RUNNABLE, REMAINDER,
                        "Idle (session active)");

                sessionManager.createSession(userID);
                systemDisplay.logEvent(userID + " ADMITTED - session started");

                // Record peak active AFTER actual admission
                if (metrics != null) {
                    metrics.recordLogin();
                    metrics.updatePeakActiveUsers(
                            admissionController.getActiveUserIDs().size());
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                systemDisplay.logEvent(userID + " admission interrupted");
                if (metrics != null) metrics.setThreadState(uid, TERMINATED, NONE,
                        "Admission interrupted");
            } finally {
                pendingAdmissions.remove(uid);
            }
        }, "Thread-Admit-" + userID);
        admitThread.setDaemon(true);
        admitThread.start();

        return true;
    }
}
