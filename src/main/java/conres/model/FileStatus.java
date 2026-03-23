package conres.model;

/**
 * Represents the current access state of a shared resource.
 * Used by SystemStateSnapshot for UI rendering (FR14, FR15).
 */
public enum FileStatus {
    IDLE,
    READING,
    WRITING
}
