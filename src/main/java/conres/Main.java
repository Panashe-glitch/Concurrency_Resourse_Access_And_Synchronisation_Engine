package conres;

import conres.application.AutoDemoRunner;
import conres.application.CommandDispatcher;
import conres.application.LocalAuthenticationService;
import conres.application.SessionController;
import conres.data.FileBackedCredentialStore;
import conres.data.LocalFileRepository;
import conres.engine.*;
import conres.interfaces.*;
import conres.model.DemoConfig;
import conres.model.UserID;
import conres.presentation.DashboardServer;
import conres.presentation.SystemDisplay;

import java.io.IOException;
import java.util.Scanner;

import static conres.engine.MetricsCollector.JavaThreadState.*;
import static conres.engine.MetricsCollector.CriticalPhase.*;

/**
 * ConRes -- Concurrent Resource Access and Synchronisation Engine.
 *
 * Single unified input loop. All commands go through one thread reading stdin.
 * Dashboard: Embedded HTTP server on port 9090 serves live concurrency
 * visualisation at http://localhost:9090.
 */
public class Main {

    private static final String DEFAULT_CREDENTIALS_FILE = "users.txt";
    private static final String DEFAULT_DATA_DIR = ".";
    private static final int DASHBOARD_PORT = 9090;
    private static final String DEFAULT_CONTENT =
            "Product Specification v1.0\n" +
            "==========================\n" +
            "This is the shared product specification file.\n" +
            "Multiple users can read this file concurrently.\n" +
            "Only one user can write to this file at a time.\n";

    public static void main(String[] args) {
        DashboardServer dashboard = null;
        boolean autoDemo = false;
        try {
            // Parse flags
            String credentialsFile = DEFAULT_CREDENTIALS_FILE;
            String dataDir = DEFAULT_DATA_DIR;
            for (String arg : args) {
                if ("--auto-demo".equalsIgnoreCase(arg)) {
                    autoDemo = true;
                } else if (credentialsFile.equals(DEFAULT_CREDENTIALS_FILE)
                        && !arg.startsWith("--")) {
                    credentialsFile = arg;
                } else if (dataDir.equals(DEFAULT_DATA_DIR)
                        && !arg.startsWith("--")) {
                    dataDir = arg;
                }
            }

            // --- DATA LAYER ---
            FileBackedCredentialStore credentialStore = new FileBackedCredentialStore(credentialsFile);
            LocalFileRepository fileRepository = new LocalFileRepository(dataDir);
            fileRepository.ensureExists(DemoConfig.RESOURCE_ID, DEFAULT_CONTENT);

            // --- CONCURRENCY ENGINE ---
            IAdmissionController admissionController =
                    new LocalAdmissionController(DemoConfig.MAX_CONCURRENT);
            IAccessCoordinator accessCoordinator =
                    new LocalAccessCoordinator();
            MetricsCollector metrics = new MetricsCollector();
            ResourceAccessManager resourceAccessManager =
                    new ResourceAccessManager(accessCoordinator, fileRepository);
            resourceAccessManager.setMetrics(metrics);
            SessionManager sessionManager =
                    new SessionManager(admissionController, accessCoordinator, resourceAccessManager);
            IStateSnapshotProvider snapshotProvider =
                    new LocalStateSnapshotProvider(admissionController, accessCoordinator);

            // --- APPLICATION LAYER ---
            IAuthenticationService authService =
                    new LocalAuthenticationService(credentialStore);
            SystemDisplay systemDisplay = new SystemDisplay(snapshotProvider);
            SessionController sessionController =
                    new SessionController(authService, admissionController, sessionManager, systemDisplay);
            sessionController.setMetrics(metrics);
            CommandDispatcher commandDispatcher =
                    new CommandDispatcher(resourceAccessManager, systemDisplay);

            resourceAccessManager.setEventListener(systemDisplay::logEvent);

            // Wire session timeout watchdog (L1 enforcement - converts assumption A3 into guarantee)
            sessionManager.setEventListener(systemDisplay::logEvent);
            sessionManager.setMetrics(metrics);
            sessionManager.startTimeoutWatchdog();

            // --- DASHBOARD SERVER ---
            dashboard = new DashboardServer(snapshotProvider, metrics,
                    systemDisplay.getEventLog(), fileRepository, DASHBOARD_PORT);
            dashboard.setControllers(sessionController, sessionManager, commandDispatcher);
            dashboard.setAllUsernames(credentialStore.getAllUsernames());
            dashboard.setUsernameToId(credentialStore.getUsernameToIdMap());
            try {
                dashboard.start();
            } catch (IOException e) {
                System.err.println("Warning: Dashboard failed on port " + DASHBOARD_PORT
                        + " (" + e.getMessage() + "). Continuing without dashboard.");
                dashboard = null;
            }

            // --- SHUTDOWN HOOK ---
            final DashboardServer dashRef = dashboard;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                sessionManager.shutdown();
                if (dashRef != null) dashRef.stop();
            }, "Thread-Shutdown"));

            // --- STARTUP BANNER ---
            System.out.println("+==============================================================+");
            System.out.println("|  ConRes -- Concurrent Resource Access and Sync Engine        |");
            System.out.println("+==============================================================+");
            System.out.printf("  Loaded %d users from %s%n", credentialStore.size(), credentialsFile);
            System.out.printf("  Resource: %s%n", DemoConfig.RESOURCE_ID);
            System.out.printf("  Max concurrent: %d | Read delay: %dms | Write delay: %dms%n",
                    DemoConfig.MAX_CONCURRENT, DemoConfig.READ_DELAY_MS, DemoConfig.WRITE_DELAY_MS);
            if (dashboard != null) {
                System.out.printf("  Dashboard: http://localhost:%d%n", DASHBOARD_PORT);
            }
            if (autoDemo) {
                System.out.println("  Mode: AUTO-DEMO (scripted scenario)");
            }
            printHelp();
            systemDisplay.printStatus();

            // --- AUTO-DEMO MODE ---
            if (autoDemo) {
                AutoDemoRunner demo = new AutoDemoRunner(
                        sessionController, sessionManager, commandDispatcher,
                        systemDisplay, metrics);
                demo.run();
            }

            // --- SINGLE INPUT LOOP ---
            Scanner scanner = new Scanner(System.in);
            while (true) {
                System.out.print("\nConRes> ");
                System.out.flush();

                if (!scanner.hasNextLine()) break;
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split("\\s+", 3);
                String cmd = parts[0].toUpperCase();

                switch (cmd) {
                    case "QUIT", "EXIT" -> {
                        sessionManager.shutdown();
                        if (dashboard != null) dashboard.stop();
                        System.out.println("ConRes terminated.");
                        return;
                    }
                    case "HELP" -> printHelp();
                    case "STATUS" -> systemDisplay.printStatus();
                    case "LOGIN" -> {
                        if (parts.length < 2) {
                            System.out.println("Usage: LOGIN <username>");
                            continue;
                        }
                        boolean authOk = sessionController.login(parts[1]);
                        if (authOk) {
                            System.out.println("Authenticating " + parts[1] + "... admission in progress.");
                        }
                    }
                    default -> {
                        String username = parts[0];
                        UserSession session = findSession(sessionManager, username);

                        if (session == null) {
                            boolean isWaiting = admissionController.getWaitingUserIDs().stream()
                                    .anyMatch(w -> w.getId().equalsIgnoreCase(username)
                                            || w.getUsername().equalsIgnoreCase(username));
                            if (isWaiting) {
                                System.out.println(username + " is still waiting for admission.");
                            } else {
                                System.out.println("Unknown command or user not logged in: " + username);
                                System.out.println("Type HELP for available commands.");
                            }
                            continue;
                        }

                        if (parts.length < 2) {
                            System.out.println("Usage: " + username + " READ | WRITE <text> | LOGOUT");
                            continue;
                        }

                        String action = parts[1].toUpperCase();
                        UserID userID = session.getUserID();

                        switch (action) {
                            case "READ" -> {
                                commandDispatcher.executeRead(session);
                                System.out.println(userID + " read started (takes "
                                        + DemoConfig.READ_DELAY_MS + "ms). Use STATUS to check.");
                            }
                            case "WRITE" -> {
                                if (parts.length < 3) {
                                    System.out.println("Usage: " + username + " WRITE <text>");
                                    continue;
                                }
                                commandDispatcher.executeWrite(session, parts[2]);
                                System.out.println(userID + " write started (takes "
                                        + DemoConfig.WRITE_DELAY_MS + "ms). Use STATUS to check.");
                            }
                            case "LOGOUT" -> {
                                // Thread lifecycle: TERMINATED
                                metrics.setThreadState(userID.getId(), TERMINATED, NONE,
                                        "Session ended, permit released");
                                sessionManager.logout(userID);
                                metrics.recordLogout();
                                systemDisplay.logEvent(userID + " LOGGED OUT - permit released");
                                systemDisplay.printStatus();
                            }
                            default -> System.out.println(
                                    "Unknown action: " + action + ". Use READ, WRITE, or LOGOUT.");
                        }
                    }
                }
            }

        } catch (IOException e) {
            System.err.println("Fatal: " + e.getMessage());
            System.err.println("Ensure 'users.txt' exists in the working directory.");
            System.exit(1);
        } finally {
            if (dashboard != null) dashboard.stop();
        }
    }

    private static UserSession findSession(SessionManager sm, String input) {
        // Try by ID first (dashboard sends IDs)
        UserSession s = sm.getSession(input);
        if (s != null) return s;
        // Try by username (CLI sends usernames)
        s = sm.getSessionByUsername(input);
        if (s != null) return s;
        // Try case variations
        s = sm.getSession(input.toUpperCase());
        if (s != null) return s;
        return sm.getSession(input.toLowerCase());
    }

    private static void printHelp() {
        System.out.println();
        System.out.println("COMMANDS:");
        System.out.println("  LOGIN <username>          Log in a user (e.g. LOGIN alice)");
        System.out.println("  <username> READ           Read the shared file");
        System.out.println("  <username> WRITE <text>   Write text to the shared file");
        System.out.println("  <username> LOGOUT         Log out and release permit");
        System.out.println("  STATUS                    Show system state + invariant checks");
        System.out.println("  HELP                      Show this help");
        System.out.println("  QUIT                      Exit the program");
        System.out.println();
        System.out.println("DEMO WALKTHROUGH:");
        System.out.println("  1. LOGIN User1            Admit first user");
        System.out.println("  2. LOGIN User2            Admit second user");
        System.out.println("  3. User1 READ             User1 starts reading (5s delay)");
        System.out.println("  4. User2 READ             User2 reads concurrently (both allowed)");
        System.out.println("  5. STATUS                 See both users reading + invariants");
        System.out.println("  6. User1 WRITE hello      User1 writes exclusively (5s delay)");
        System.out.println("  7. LOGIN User3, User4     Fill remaining slots");
        System.out.println("  8. LOGIN User5            User5 enters WAITING (all 4 slots full)");
        System.out.println("  9. User1 LOGOUT           Frees a slot -> User5 auto-admitted");
        System.out.println("  10. STATUS                See User5 now active, dashboard live");
    }
}
