package conres.model;

import java.util.Optional;

/**
 * Generic result type for operations that can succeed or fail.
 * Avoids exception-driven control flow for expected failures (e.g., IO errors).
 */
public final class Result<T> {
    private final T value;
    private final Exception error;
    private final boolean success;

    private Result(T value, Exception error, boolean success) {
        this.value = value;
        this.error = error;
        this.success = success;
    }

    public static <T> Result<T> success(T value) {
        return new Result<>(value, null, true);
    }

    public static <T> Result<T> success() {
        return new Result<>(null, null, true);
    }

    public static <T> Result<T> failure(Exception error) {
        return new Result<>(null, error, false);
    }

    public boolean isSuccess() { return success; }
    public T getValue() { return value; }
    public Optional<Exception> getError() { return Optional.ofNullable(error); }
}
