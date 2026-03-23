package conres.engine;

import conres.interfaces.IAccessCoordinator;
import conres.interfaces.IAdmissionController;
import conres.interfaces.IStateSnapshotProvider;
import conres.model.*;

import java.util.*;

/**
 * Produces immutable snapshots of system state by sequentially reading
 * concurrent collections (Approach B -- documented transient inconsistency).
 *
 * S10 caveat: Snapshot reads are NOT atomic across all collections.
 * A user could transition between active/waiting during snapshot assembly.
 * This is acceptable for a display refresh cycle (<=500ms) and documented
 * as a design trade-off vs the complexity of a global snapshot lock.
 *
 * Satisfies: NFR6 (observability), FR12-FR15.
 */
public class LocalStateSnapshotProvider implements IStateSnapshotProvider {

    private final IAdmissionController admissionController;
    private final IAccessCoordinator accessCoordinator;

    public LocalStateSnapshotProvider(IAdmissionController admissionController,
                                      IAccessCoordinator accessCoordinator) {
        this.admissionController = admissionController;
        this.accessCoordinator = accessCoordinator;
    }

    @Override
    public SystemStateSnapshot getSnapshot() {
        // Sequential reads -- order chosen to minimise visual inconsistency
        List<UserID> active = admissionController.getActiveUserIDs();
        List<UserID> waiting = admissionController.getWaitingUserIDs();
        Set<UserID> readers = accessCoordinator.getCurrentReaders();
        Optional<UserID> writer = accessCoordinator.getCurrentWriter();
        int version = accessCoordinator.getVersion();
        int permits = admissionController.getAvailablePermits();

        // Derive file status from reader/writer state
        FileStatus fileStatus;
        if (writer.isPresent()) {
            fileStatus = FileStatus.WRITING;
        } else if (!readers.isEmpty()) {
            fileStatus = FileStatus.READING;
        } else {
            fileStatus = FileStatus.IDLE;
        }

        return new SystemStateSnapshot(
                active,
                waiting,
                List.copyOf(readers),
                writer,
                DemoConfig.RESOURCE_ID,
                fileStatus,
                version,
                permits
        );
    }
}
