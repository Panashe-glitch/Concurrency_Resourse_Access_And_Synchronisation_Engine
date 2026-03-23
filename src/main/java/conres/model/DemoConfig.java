package conres.model;

/**
 * Configuration for demo mode vs stress testing mode.
 * Demo mode: artificial delays make blocking visible to assessor.
 * Stress mode: zero delays for Phase 7 correctness testing.
 */
public final class DemoConfig {
    public static boolean DEMO_MODE = true; // set false by StressTest
    public static final long READ_DELAY_MS = 5000; // 5s: assessor window to type STATUS
    public static final long WRITE_DELAY_MS = 5000; // 5s: assessor window to type STATUS
    public static final int MAX_CONCURRENT = 4;
    public static final long REFRESH_INTERVAL_MS = 5000;
    public static final String RESOURCE_ID = "ProductSpecification.txt";

    /**
     * Session inactivity timeout in ms. Converts L1 assumption A3 into enforcement.
     * After this period with no READ/WRITE/LOGOUT, watchdog forces logout.
     */
    public static long SESSION_TIMEOUT_MS = 60_000; // 2 minutes; tests can override

    private DemoConfig() {
    } // utility class
}
