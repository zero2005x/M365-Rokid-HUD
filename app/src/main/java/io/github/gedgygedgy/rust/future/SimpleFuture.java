package io.github.gedgygedgy.rust.future;

import io.github.gedgygedgy.rust.task.PollResult;
import io.github.gedgygedgy.rust.task.Waker;

/**
 * A simple implementation of {@link Future} that can be resolved by calling
 * {@link #wake(Object)} or {@link #wakeWithThrowable(Throwable)}.
 * <p>
 * This class is not thread-safe. It is intended to be used in a single-threaded
 * environment.
 *
 * @param <T> the type of the value that will be produced by this {@link Future}
 */
public class SimpleFuture<T> implements Future<T> {
    private boolean ready = false;
    private T result;
    private Throwable throwable;

    @Override
    public PollResult<T> poll(Waker waker) {
        if (ready) {
            if (throwable != null) {
                return PollResult.fromThrowable(throwable);
            } else {
                return PollResult.fromValue(result);
            }
        } else {
            return PollResult.pending();
        }
    }

    /**
     * Wake this {@link Future} with a value.
     *
     * @param result the value to wake with
     */
    public void wake(T result) {
        if (ready) {
            throw new IllegalStateException("Future is already ready");
        }
        this.result = result;
        this.ready = true;
    }

    /**
     * Wake this {@link Future} with a throwable.
     *
     * @param throwable the throwable to wake with
     */
    public void wakeWithThrowable(Throwable throwable) {
        if (ready) {
            throw new IllegalStateException("Future is already ready");
        }
        this.throwable = throwable;
        this.ready = true;
    }
}