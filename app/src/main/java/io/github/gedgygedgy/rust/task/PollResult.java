package io.github.gedgygedgy.rust.task;

import java.util.Objects;

/**
 * The result of polling a {@link io.github.gedgygedgy.rust.future.Future} or
 * {@link io.github.gedgygedgy.rust.stream.Stream}.
 * <p>
 * A result is in exactly one of three states:
 * <ul>
 *   <li><b>pending</b> - not ready yet; {@link #isPending()} is true and both
 *       {@link #getValue()} and {@link #getThrowable()} are null.</li>
 *   <li><b>value</b> - completed successfully; {@link #getValue()} is non-null.</li>
 *   <li><b>throwable</b> - completed with an error; {@link #getThrowable()} is
 *       non-null.</li>
 * </ul>
 * Null values and null throwables are rejected, so the state can always be
 * determined unambiguously and {@link #getValue()} being null implies the
 * result is either pending or a failure.
 */
public class PollResult<T> {
    private final boolean pending;
    private final T value;
    private final Throwable throwable;

    private PollResult(boolean pending, T value, Throwable throwable) {
        this.pending = pending;
        this.value = value;
        this.throwable = throwable;
    }

    /**
     * Create a pending result.
     *
     * @param <T> the type of the value
     * @return a pending result
     */
    public static <T> PollResult<T> pending() {
        return new PollResult<>(true, null, null);
    }

    /**
     * Create a result with a value.
     *
     * @param value the value
     * @param <T> the type of the value
     * @return a result with a value
     */
    public static <T> PollResult<T> fromValue(T value) {
        // Rejecting null keeps the three states distinguishable: a null value
        // would be indistinguishable from a pending result via getValue().
        Objects.requireNonNull(value, "value must not be null; use pending() for a not-ready result");
        return new PollResult<>(false, value, null);
    }

    /**
     * Check if this result is ready and carries a value.
     *
     * @return true if the result completed successfully
     */
    public boolean isValue() {
        return !pending && throwable == null;
    }

    /**
     * Check if this result is ready and carries a throwable.
     *
     * @return true if the result completed with an error
     */
    public boolean isThrowable() {
        return !pending && throwable != null;
    }

    /**
     * Create a result with a throwable.
     *
     * @param throwable the throwable
     * @param <T> the type of the value
     * @return a result with a throwable
     */
    public static <T> PollResult<T> fromThrowable(Throwable throwable) {
        // A null throwable would produce a completed-but-empty result
        // (pending=false, value=null, throwable=null). Callers that detect
        // failure via getThrowable() != null would read that as a successful
        // null-value result and skip error handling entirely.
        Objects.requireNonNull(throwable, "throwable must not be null");
        return new PollResult<>(false, null, throwable);
    }

    /**
     * Check if this result is pending.
     *
     * @return true if pending, false otherwise
     */
    public boolean isPending() {
        return pending;
    }

    /**
     * Get the value if this result has one.
     *
     * @return the value, or null if pending or has a throwable
     */
    public T getValue() {
        return value;
    }

    /**
     * Get the throwable if this result has one.
     *
     * @return the throwable, or null if pending or has a value
     */
    public Throwable getThrowable() {
        return throwable;
    }
}