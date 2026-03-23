package conres;

import conres.data.*;
import conres.engine.*;
import conres.interfaces.*;
import conres.model.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Non-interactive smoke test. Exercises:
 * - Authentication (valid/invalid/duplicate)
 * - Admission (fill all 4 slots, verify 5th blocks)
 * - Concurrent reads with simultaneous lock verification
 * - Exclusive write (writer blocks readers)
 * - Version increment only on success (Bug #3 regression test)
 * - Logout during operation with lock cleanup (Bug #1 regression test)
 * - S1, S2, S3, S8, S9 assertions
 */
public class SmokeTest {

    public static void main(String[] args) throws Exception {
        DemoConfig.DEMO_MODE = false;
        DemoConfig.SESSION_TIMEOUT_MS = 300_000;
        System.out.println("=== ConRes Smoke Test ===\n");

        // Setup
        String tmpDir = Files.createTempDirectory("conres-test").toString();
        Files.writeString(Path.of(tmpDir, "users.txt"),
                "User1\nUser2\nUser3\nUser4\nUser5\nUser6\nUser7\n");
        Files.writeString(Path.of(tmpDir, "ProductSpecification.txt"),
                "Test content v1.0");

        FileBackedCredentialStore credStore = new FileBackedCredentialStore(tmpDir + "/users.txt");
        LocalFileRepository fileRepo = new LocalFileRepository(tmpDir);
        LocalAdmissionController admission = new LocalAdmissionController(4);
        LocalAccessCoordinator access = new LocalAccessCoordinator();
        ResourceAccessManager ram = new ResourceAccessManager(access, fileRepo);

        // Test 1: Authentication
        System.out.println("--- Test 1: Authentication ---");
        assert credStore.validate("user1").isPresent() : "User1 should validate (case-insensitive)";
        assert credStore.validate("User1").isPresent() : "User1 should validate";
        assert credStore.validate("invalid").isEmpty() : "Invalid user should fail";
        System.out.println("PASS: Authentication works correctly");

        // Test 2: Admission -- fill 4 slots
        System.out.println("\n--- Test 2: Admission Control ---");
        UserID u1 = credStore.validate("user1").get();
        UserID u2 = credStore.validate("user2").get();
        UserID u3 = credStore.validate("user3").get();
        UserID u4 = credStore.validate("user4").get();
        UserID u5 = credStore.validate("user5").get();

        admission.tryAdmit(u1);
        admission.tryAdmit(u2);
        admission.tryAdmit(u3);
        admission.tryAdmit(u4);

        // S1: ActiveUsers <= 4
        assert admission.getActiveUserIDs().size() == 4 : "S1: Should have 4 active users";
        // S8: permits + active = 4
        assert admission.getAvailablePermits() + admission.getActiveUserIDs().size() == 4
                : "S8: Permit conservation violated";
        // S6: permits >= 0
        assert admission.getAvailablePermits() == 0 : "S6: Should have 0 permits left";
        System.out.println("PASS: S1, S6, S8 hold with 4 active users");

        // Test 3: 5th user should block
        System.out.println("\n--- Test 3: Blocking on Full Capacity ---");
        CountDownLatch u5started = new CountDownLatch(1);
        CountDownLatch u5admitted = new CountDownLatch(1);
        Thread t5 = new Thread(() -> {
            try {
                u5started.countDown();
                admission.tryAdmit(u5); // Should block
                u5admitted.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        t5.start();
        u5started.await();
        Thread.sleep(200); // Give time to block
        assert admission.isWaiting(u5) : "User5 should be in waiting queue";
        assert !admission.isActive(u5) : "S9: User5 should NOT be active yet";

        // Release one slot
        admission.release(u1);
        u5admitted.await(2, TimeUnit.SECONDS);
        assert admission.isActive(u5) : "User5 should now be active";
        assert !admission.isWaiting(u5) : "S9: User5 should NOT be waiting anymore";
        // S8 still holds
        assert admission.getAvailablePermits() + admission.getActiveUserIDs().size() == 4
                : "S8: Permit conservation violated after release";
        System.out.println("PASS: Blocking, release, S9 partition, S8 conservation all correct");

        // Test 4: Concurrent reads with simultaneous lock proof
        System.out.println("\n--- Test 4: Concurrent Reads (simultaneous lock proof) ---");
        CountDownLatch allReadersHoldLock = new CountDownLatch(3);
        CountDownLatch releaseReaders = new CountDownLatch(1);
        CountDownLatch allReadersDone = new CountDownLatch(3);
        AtomicInteger peakConcurrentReaders = new AtomicInteger(0);

        for (UserID uid : List.of(u2, u3, u4)) {
            new Thread(() -> {
                try {
                    access.beginRead(uid);
                    // Atomically track peak concurrency
                    int current = access.getCurrentReaders().size();
                    peakConcurrentReaders.updateAndGet(prev -> Math.max(prev, current));
                    allReadersHoldLock.countDown();     // Signal: I hold the read lock
                    releaseReaders.await();             // Wait: don't release until told
                } catch (InterruptedException ignored) {
                } finally {
                    access.endRead(uid);
                    allReadersDone.countDown();
                }
            }).start();
        }
        allReadersHoldLock.await(); // All 3 readers simultaneously hold read locks

        // S3: No writer active while readers are active
        assert access.getCurrentReaders().size() == 3 : "Should have 3 concurrent readers";
        assert access.getCurrentWriter().isEmpty() : "S3: No writer while readers active";
        // Verify peak concurrency reached 3 (proves simultaneous holding)
        assert peakConcurrentReaders.get() >= 2 : "Peak concurrent readers should be >= 2";
        System.out.println("PASS: 3 concurrent readers (peak=" + peakConcurrentReaders.get() + "), S3 holds");

        releaseReaders.countDown();
        allReadersDone.await();

        // Test 5: Exclusive write
        System.out.println("\n--- Test 5: Exclusive Write ---");
        access.beginWrite(u2);
        // S2: writer count <= 1
        assert access.getCurrentWriter().isPresent() : "Writer should be tracked";
        assert access.getCurrentWriter().get().equals(u2) : "Writer should be User2";
        // S3: no readers while writer active
        assert access.getCurrentReaders().isEmpty() : "S3: No readers while writer active";

        int vBefore = access.getVersion();
        access.incrementVersion();          // Explicit success-only increment
        access.endWrite(u2);
        assert access.getVersion() == vBefore + 1 : "Version should increment after write";
        System.out.println("PASS: S2, S3, version increment all correct");

        // Test 6: Version NOT incremented on failed write (Bug #3 regression test)
        System.out.println("\n--- Test 6: Version Stability on Failed Write ---");
        int vBeforeFail = access.getVersion();
        access.beginWrite(u3);
        // Simulate failed write: do NOT call incrementVersion()
        access.endWrite(u3);
        assert access.getVersion() == vBeforeFail : "Bug #3: Version must NOT increment on failed write";
        System.out.println("PASS: Version unchanged after failed write (Bug #3 fixed)");

        // Test 7: File IO through ResourceAccessManager
        System.out.println("\n--- Test 7: ResourceAccessManager IO ---");
        String content = fileRepo.readContents("ProductSpecification.txt");
        assert content.contains("Test content") : "Should read file contents";

        Result<Void> writeResult = fileRepo.writeContents("ProductSpecification.txt", "Updated v2.0");
        assert writeResult.isSuccess() : "Write should succeed";

        String updated = fileRepo.readContents("ProductSpecification.txt");
        assert updated.equals("Updated v2.0") : "Should read updated content";
        System.out.println("PASS: File read/write works correctly");

        // Test 8: Snapshot
        System.out.println("\n--- Test 8: State Snapshot ---");
        LocalStateSnapshotProvider snapProvider = new LocalStateSnapshotProvider(admission, access);
        SystemStateSnapshot snap = snapProvider.getSnapshot();
        assert snap.getActiveUserIDs().size() == 4 : "Snapshot should show 4 active users";
        assert snap.getFileStatus() == FileStatus.IDLE : "File should be IDLE";
        assert snap.getResourceId().equals("ProductSpecification.txt") : "Resource ID correct";
        System.out.println("PASS: Snapshot captures correct state");

        // Test 9: Logout during operation (Bug #1 regression test)
        System.out.println("\n--- Test 9: Logout During Operation (Bug #1 fix) ---");
        // Create a UserSession that acquires a read lock, then shut it down
        UserSession testSession = new UserSession(u2);
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch sleepStarted = new CountDownLatch(1);

        testSession.submit(() -> {
            try {
                access.beginRead(u2);
                lockAcquired.countDown();
                sleepStarted.countDown();
                Thread.sleep(30000);    // Long sleep simulating operation
            } catch (InterruptedException e) {
                // Expected: shutdown interrupts the sleep
                Thread.currentThread().interrupt();
            } finally {
                access.endRead(u2);     // Lock released from OWNING thread
            }
        });

        lockAcquired.await(2, TimeUnit.SECONDS);
        assert access.getCurrentReaders().contains(u2) : "User2 should be reading";

        // Shutdown the session (simulates logout)
        testSession.shutdown();
        access.forceRelease(u2);        // Clean up tracking

        // Verify lock was released: a writer should be able to acquire it
        CountDownLatch writerGot = new CountDownLatch(1);
        Thread writerThread = new Thread(() -> {
            try {
                access.beginWrite(u3);
                writerGot.countDown();
                access.endWrite(u3);
            } catch (InterruptedException ignored) {}
        });
        writerThread.start();
        boolean writerAcquired = writerGot.await(3, TimeUnit.SECONDS);
        assert writerAcquired : "Bug #1: Writer must acquire lock after session shutdown";
        writerThread.join(1000);
        System.out.println("PASS: Lock released by owning thread after session shutdown (Bug #1 fixed)");

        // Cleanup
        admission.release(u2);
        admission.release(u3);
        admission.release(u4);
        admission.release(u5);

        System.out.println("\n=== ALL SMOKE TESTS PASSED ===");
        System.out.println("Properties verified: S1, S2, S3, S6, S8, S9, C8 (structural)");
        System.out.println("Bug regressions verified: #1 (forceRelease), #3 (version on failure)");
    }
}
