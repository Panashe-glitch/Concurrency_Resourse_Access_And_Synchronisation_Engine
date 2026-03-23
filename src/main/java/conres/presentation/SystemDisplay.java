package conres.presentation;

import conres.interfaces.IStateSnapshotProvider;
import conres.model.*;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Console-based system display -- event-driven, Windows-compatible.
 * Prints a status panel on demand (after state changes) rather than
 * using ANSI clear-screen codes which fail on Windows PowerShell.
 *
 * Event log is maintained as a thread-safe list of 100 entries (expanded
 * from 10 to support the dashboard's scrollable event log). The CLI
 * still shows the last 4 entries; the dashboard shows all 100.
 *
 * Satisfies: NFR6 (observability), NFR7 (separation of concerns).
 */
public class SystemDisplay {

    private static final int MAX_EVENTS = 500;
    private static final int CLI_DISPLAY_EVENTS = 4;

    private final IStateSnapshotProvider snapshotProvider;
    private final List<String> eventLog = new CopyOnWriteArrayList<>();
    private volatile boolean running = false;
    private Thread displayThread;

    public SystemDisplay(IStateSnapshotProvider snapshotProvider) {
        this.snapshotProvider = snapshotProvider;
    }

    /** Returns the shared event log for dashboard access. Thread-safe. */
    public List<String> getEventLog() {
        return eventLog;
    }

    public void start() { running = true; }

    public void stop() {
        running = false;
        if (displayThread != null) displayThread.interrupt();
    }

    /** Adds an event to the log (thread-safe). */
    public synchronized void logEvent(String event) {
        String timestamped = String.format("[%tT] %s",
                System.currentTimeMillis(), event);
        eventLog.add(timestamped);
        // Synchronized trim prevents duplicate-add races
        while (eventLog.size() > MAX_EVENTS) {
            eventLog.remove(0);
        }
    }

    /** Prints a status panel to stdout. Thread-safe via synchronized. */
    public synchronized void printStatus() {
        try {
            SystemStateSnapshot snapshot = snapshotProvider.getSnapshot();
            StringBuilder sb = new StringBuilder();

            sb.append("\n");
            sb.append("+==============================================================+\n");
            sb.append("|         ConRes -- Concurrent Resource Access Engine           |\n");
            sb.append("+==============================================================+\n");

            // --- ADMISSION STATUS ---
            sb.append("| ADMISSION:  ");
            List<UserID> active = snapshot.getActiveUserIDs();
            if (active.isEmpty()) {
                sb.append("Active: (none)");
            } else {
                sb.append("Active: ");
                for (int i = 0; i < active.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(active.get(i));
                }
            }
            sb.append(String.format("  [%d/%d slots]", active.size(), DemoConfig.MAX_CONCURRENT));
            sb.append("\n");

            sb.append("|             ");
            List<UserID> waiting = snapshot.getWaitingUserIDs();
            if (waiting.isEmpty()) {
                sb.append("Waiting: (none)");
            } else {
                sb.append("Waiting: ");
                for (int i = 0; i < waiting.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(waiting.get(i));
                }
            }
            sb.append("  Permits: ").append(snapshot.getAvailablePermits());
            sb.append("\n");

            // --- FILE STATUS ---
            sb.append("| FILE:       ");
            sb.append(snapshot.getResourceId());
            sb.append(" (v").append(snapshot.getVersionCounter()).append(")  Status: ");

            switch (snapshot.getFileStatus()) {
                case IDLE -> sb.append("IDLE");
                case READING -> {
                    sb.append("Reading ... ");
                    List<UserID> readers = snapshot.getCurrentReaders();
                    for (int i = 0; i < readers.size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(readers.get(i));
                    }
                }
                case WRITING -> {
                    sb.append("Updating ... ");
                    snapshot.getCurrentWriter().ifPresent(sb::append);
                }
            }
            sb.append("\n");

            // --- RUNTIME INVARIANT CHECKS ---
            sb.append("| INVARIANTS: ");
            int activeCount = active.size();
            int permits = snapshot.getAvailablePermits();
            boolean writerActive = snapshot.getCurrentWriter().isPresent();
            int readerCount = snapshot.getCurrentReaders().size();

            boolean s1 = activeCount <= DemoConfig.MAX_CONCURRENT;
            sb.append("S1:").append(s1 ? "OK" : "FAIL").append("  ");

            sb.append("S2:").append(writerActive ? "1" : "0").append("<=1  ");

            boolean s3 = !(writerActive && readerCount > 0);
            sb.append("S3:").append(s3 ? "OK" : "FAIL").append("  ");

            boolean s8 = (permits + activeCount) == DemoConfig.MAX_CONCURRENT;
            sb.append("S8:").append(permits).append("+").append(activeCount)
                    .append("=").append(permits + activeCount)
                    .append(s8 ? " OK" : " FAIL");
            sb.append("\n");

            // --- EVENT LOG (last 4 for CLI) ---
            sb.append("+--------------------------------------------------------------+\n");
            if (eventLog.isEmpty()) {
                sb.append("| (no events yet)\n");
            } else {
                int start = Math.max(0, eventLog.size() - CLI_DISPLAY_EVENTS);
                for (int i = start; i < eventLog.size(); i++) {
                    sb.append("| ").append(eventLog.get(i)).append("\n");
                }
            }
            sb.append("+==============================================================+\n");

            System.out.print(sb);
            System.out.flush();

        } catch (Exception e) {
            // Display errors should never crash the system
        }
    }
}
