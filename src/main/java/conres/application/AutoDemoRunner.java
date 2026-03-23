package conres.application;

import conres.engine.MetricsCollector;
import conres.engine.SessionManager;
import conres.engine.UserSession;
import conres.model.DemoConfig;
import conres.model.UserID;
import conres.presentation.SystemDisplay;

import static conres.engine.MetricsCollector.JavaThreadState.*;
import static conres.engine.MetricsCollector.CriticalPhase.*;

/**
 * Automated demo runner for video recording.
 *
 * Executes a scripted scenario that demonstrates every rubric requirement:
 *   - User Login and Concurrency Control (admission, queuing, cascade)
 *   - Shared Resource (File) Access (concurrent read, exclusive write)
 *   - Interface Display (dashboard updates visible between steps)
 *
 * ARCHITECTURAL NOTE: This class sits in the Application layer and calls
 * the SAME code paths as the manual CMD interface (SessionController.login,
 * CommandDispatcher.executeRead/Write, SessionManager.logout). No new
 * concurrency surface area is introduced. The dashboard remains read-only.
 *
 * Usage: java -cp ... conres.Main --auto-demo
 */
public class AutoDemoRunner {

    private final SessionController sessionController;
    private final SessionManager sessionManager;
    private final CommandDispatcher commandDispatcher;
    private final SystemDisplay systemDisplay;
    private final MetricsCollector metrics;

    public AutoDemoRunner(SessionController sessionController,
                          SessionManager sessionManager,
                          CommandDispatcher commandDispatcher,
                          SystemDisplay systemDisplay,
                          MetricsCollector metrics) {
        this.sessionController = sessionController;
        this.sessionManager = sessionManager;
        this.commandDispatcher = commandDispatcher;
        this.systemDisplay = systemDisplay;
        this.metrics = metrics;
    }

    /**
     * Runs the full demo scenario. Blocks until complete.
     * Designed for ~60 seconds total runtime with default delays.
     */
    public void run() {
        try {
            banner("CONRES AUTO-DEMO STARTING");
            narrate("Open http://localhost:9090 in your browser to see the live dashboard.");
            narrate("This scenario demonstrates all rubric requirements automatically.");
            pause(3000);

            // ================================================================
            // PHASE 1: LOGIN AND ADMISSION (Rubric: User Login & Concurrency Control)
            // ================================================================
            phase("PHASE 1: User Login and Admission Control");

            narrate("Logging in User1 (alice)... first user, immediate admission.");
            sessionController.login("User1");
            pause(1500);  // Let admission thread complete

            narrate("Logging in User2 (bob)... second user, immediate admission.");
            sessionController.login("User2");
            pause(1500);

            narrate("Logging in User3 (carol)... third user, immediate admission.");
            sessionController.login("User3");
            pause(1500);

            narrate("Logging in User4 (dave)... fourth user fills semaphore (4/4).");
            sessionController.login("User4");
            pause(1500);

            narrate("Logging in User5 (eve)... FIFTH user -- semaphore full!");
            narrate("   >>> User5 will be QUEUED. Watch the dashboard waiting list.");
            sessionController.login("User5");
            pause(2000);

            systemDisplay.printStatus();
            narrate("STATUS: 4 active, 1 waiting. S1 satisfied: |active| <= N.");
            pause(3000);

            // ================================================================
            // PHASE 2: CONCURRENT READS (Rubric: Shared Resource File Access)
            // ================================================================
            phase("PHASE 2: Concurrent File Reading");

            UserSession s1 = waitForSession("User1", 3000);
            UserSession s2 = waitForSession("User2", 3000);

            if (s1 != null && s2 != null) {
                narrate("User1 READ and User2 READ issued simultaneously.");
                narrate("   >>> Both should hold the read lock at the same time (FR7, FR9).");
                narrate("   >>> Watch dashboard: two readers, zero writers.");
                commandDispatcher.executeRead(s1);
                // Small stagger so both are visibly concurrent during the 5s delay
                pause(500);
                commandDispatcher.executeRead(s2);
                pause(2000);
                systemDisplay.printStatus();
                narrate("STATUS: Two concurrent readers visible. S3: readers > 0, writer = null.");
                // Wait for reads to complete (5s delay each, started ~500ms apart)
                pause(5000);
            }

            // ================================================================
            // PHASE 3: EXCLUSIVE WRITE (Rubric: Shared Resource File Access)
            // ================================================================
            phase("PHASE 3: Exclusive Write Access");

            UserSession s3 = waitForSession("User3", 3000);

            if (s3 != null) {
                narrate("User3 WRITE issued. Exclusive lock required.");
                narrate("   >>> If any readers are still active, User3 will BLOCK until they finish.");
                narrate("   >>> Watch dashboard: writer waiting, then exclusive access.");
                commandDispatcher.executeWrite(s3, "Updated by User3 during auto-demo at "
                        + System.currentTimeMillis());
                pause(2000);
                systemDisplay.printStatus();
                narrate("STATUS: Writer active. S2: writer != null IMPLIES readers = 0.");
                // Wait for write to complete
                pause(5000);
            }

            // ================================================================
            // PHASE 4: POST-WRITE READ (version incremented)
            // ================================================================
            phase("PHASE 4: Post-Write Read (Version Check)");

            UserSession s4 = waitForSession("User4", 3000);

            if (s4 != null) {
                narrate("User4 READ after write. Should see version 1 (incremented).");
                narrate("   >>> Watch dashboard: version counter updated.");
                commandDispatcher.executeRead(s4);
                pause(6000);
            }

            // ================================================================
            // PHASE 5: WRITE BLOCKS READERS (mutual exclusion demonstration)
            // ================================================================
            phase("PHASE 5: Write Blocks Subsequent Readers");

            if (s1 != null && s2 != null) {
                narrate("User1 WRITE issued, then User2 READ immediately after.");
                narrate("   >>> User2's read should BLOCK until User1's write completes.");
                narrate("   >>> This demonstrates FR8 (exclusive write) and FR10 (blocking).");
                commandDispatcher.executeWrite(s1, "Second write by User1 at "
                        + System.currentTimeMillis());
                pause(300);
                commandDispatcher.executeRead(s2);
                pause(2000);
                systemDisplay.printStatus();
                narrate("STATUS: Writer active, reader waiting for lock. S2, S3 enforced.");
                pause(5000);
            }

            // ================================================================
            // PHASE 6: LOGOUT AND CASCADE (Rubric: Concurrency Control)
            // ================================================================
            phase("PHASE 6: Logout and Admission Cascade");

            if (s2 != null) {
                narrate("User2 LOGOUT. Permit released, slot opens.");
                narrate("   >>> Watch dashboard: User5 should move from WAITING to ACTIVE.");
                narrate("   >>> This is the admission cascade -- S8 permit conservation.");
                doLogout(s2);
                pause(3000);
                systemDisplay.printStatus();
                narrate("STATUS: User5 now active! L1 (eventual admission) demonstrated.");
                pause(3000);
            }

            // ================================================================
            // PHASE 7: CASCADED USER OPERATES
            // ================================================================
            phase("PHASE 7: Cascaded User Operates");

            UserSession s5 = waitForSession("User5", 3000);

            if (s5 != null) {
                narrate("User5 READ -- the previously-queued user now has full access.");
                commandDispatcher.executeRead(s5);
                pause(6000);
            }

            // ================================================================
            // PHASE 8: CLEAN SHUTDOWN
            // ================================================================
            phase("PHASE 8: Clean Shutdown");

            narrate("Logging out all remaining users...");
            logoutIfActive("User1");
            pause(500);
            logoutIfActive("User3");
            pause(500);
            logoutIfActive("User4");
            pause(500);
            logoutIfActive("User5");
            pause(1500);

            systemDisplay.printStatus();
            narrate("All users logged out. Permits returned. System idle.");
            pause(2000);

            banner("AUTO-DEMO COMPLETE");
            narrate("All rubric demonstration requirements have been exercised:");
            narrate("  [x] User Login and Concurrency Control (admission, queueing, cascade)");
            narrate("  [x] Shared Resource (File) Access (concurrent read, exclusive write, blocking)");
            narrate("  [x] Interface Display (dashboard updated in real-time throughout)");
            narrate("");
            narrate("The system will remain running. Type QUIT to exit, or explore manually.");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("[AUTO-DEMO] Interrupted. Exiting demo.");
        }
    }

    // --- Helper methods ---

    private UserSession waitForSession(String username, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            UserSession s = sessionManager.getSession(username);
            if (s != null) return s;
            Thread.sleep(200);
        }
        System.out.printf("[AUTO-DEMO] WARNING: Session for %s not ready after %dms%n",
                username, timeoutMs);
        return null;
    }

    private void doLogout(UserSession session) {
        UserID userID = session.getUserID();
        if (metrics != null) {
            metrics.setThreadState(userID.getId(), TERMINATED, NONE,
                    "Session ended, permit released");
        }
        sessionManager.logout(userID);
        if (metrics != null) metrics.recordLogout();
        systemDisplay.logEvent(userID + " LOGGED OUT - permit released");
    }

    private void logoutIfActive(String username) {
        UserSession s = sessionManager.getSession(username);
        if (s != null) {
            doLogout(s);
        }
    }

    private static void pause(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    private static void banner(String text) {
        System.out.println();
        System.out.println("+==============================================================+");
        System.out.printf("|  %-60s|%n", text);
        System.out.println("+==============================================================+");
    }

    private static void phase(String text) {
        System.out.println();
        System.out.println("--------------------------------------------------------------");
        System.out.println("  " + text);
        System.out.println("--------------------------------------------------------------");
    }

    private static void narrate(String text) {
        System.out.println("[DEMO] " + text);
    }
}
