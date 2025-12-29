package io.github.gedgygedgy.rust.task;

/**
 * The result of polling a {@link io.github.gedgygedgy.rust.future.Future} or
 * {@link io.github.gedgygedgy.rust.stream.Stream}.
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
        return new PollResult<>(false, value, null);
    }

    /**
     * Create a result with a throwable.
     *
     * @param throwable the throwable
     * @param <T> the type of the value
     * @return a result with a throwable
     */
    public static <T> PollResult<T> fromThrowable(Throwable throwable) {
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