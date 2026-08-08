package io.github.gedgygedgy.rust.future;

import io.github.gedgygedgy.rust.task.PollResult;
import io.github.gedgygedgy.rust.task.Waker;
import java.util.Objects;

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

    /**
     * The {@link Waker} from the most recent {@link #poll} that returned
     * pending. Only the newest one can re-schedule the task, so each poll
     * replaces it.
     */
    private Waker pendingWaker;

    @Override
    public PollResult<T> poll(Waker waker) {
        if (ready) {
            this.pendingWaker = null;
            if (throwable != null) {
                return PollResult.fromThrowable(throwable);
            } else {
                return PollResult.fromValue(result);
            }
        } else {
            // Retain the waker so wake()/wakeWithThrowable() can notify the
            // executor. Dropping it meant an executor that re-polls only on
            // wake notifications never learned the future became ready, and the
            // future stalled indefinitely.
            this.pendingWaker = waker;
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
        notifyWaker();
    }

    /**
     * Wake this {@link Future} with a throwable.
     *
     * @param throwable the throwable to wake with, must not be null
     * @throws NullPointerException if {@code throwable} is null
     */
    public void wakeWithThrowable(Throwable throwable) {
        if (ready) {
            throw new IllegalStateException("Future is already ready");
        }
        // A null throwable would mark the future ready while leaving throwable
        // null, so poll()'s `throwable != null` check falls through and
        // silently completes the future with a null value instead of an error.
        this.throwable = Objects.requireNonNull(throwable, "throwable must not be null");
        this.ready = true;
        notifyWaker();
    }

    /** Invokes the pending waker at most once. */
    private void notifyWaker() {
        Waker waker = this.pendingWaker;
        if (waker != null) {
            this.pendingWaker = null;
            waker.wake();
        }
    }
}