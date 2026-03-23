package conres.interfaces;

import conres.model.SystemStateSnapshot;

/**
 * Provides immutable snapshots of system state for the presentation layer.
 * CW1: LocalStateSnapshotProvider (polls concurrent collections).
 * CW2: PubSubNotificationProvider (push-based via pub-sub channel).
 */
public interface IStateSnapshotProvider {
    SystemStateSnapshot getSnapshot();
}
