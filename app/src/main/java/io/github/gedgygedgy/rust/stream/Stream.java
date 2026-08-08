package io.github.gedgygedgy.rust.stream;

import io.github.gedgygedgy.rust.task.PollResult;
import io.github.gedgygedgy.rust.task.Waker;

/**
 * Interface for allowing Rust code to interact with Java code that produces
 * a stream of values. The intention of this interface is for asynchronous
 * Rust code to directly call Java code that returns a {@link Stream}, and
 * then poll the {@link Stream} using a {@code jni_utils::stream::JStream}.
 * In this way, the complexities of interacting with asynchronous Java code can
 * be abstracted into a simple {@code jni_utils::stream::JStream} that Rust
 * code can iterate over.
 * <p>
 * In general, you will probably want to use {@link QueueStream} rather than
 * implementing this interface directly: it already implements the waker and
 * exhaustion contracts described below.
 *
 * @param <T> the type of the values that will be produced by this {@link Stream}
 */
public interface Stream<T> {
    /**
     * Attempt to get the next value from this {@link Stream}. This method will be called
     * repeatedly by the Rust runtime until the {@link Stream} is exhausted.
     * <p>
     * This method should not block. If the {@link Stream} is not ready, it
     * should return {@link PollResult#pending()} and arrange for the
     * {@link Waker} to be called when the {@link Stream} becomes ready.
     *
     * <h4>Exhaustion</h4>
     * {@link PollResult} has no dedicated terminal state, so a {@link Stream}
     * that is backed by a finite source signals exhaustion by returning
     * {@link PollResult#fromThrowable(Throwable)} with an appropriate
     * end-of-stream exception. A ready result with a null value is <em>not</em>
     * a valid terminator: {@link PollResult#fromValue(Object)} rejects null
     * precisely so that "no more items" can never be confused with "an item
     * that happens to be null".
     * <p>
     * A {@link Stream} that never terminates (the common case here - a live
     * feed of BLE notifications) simply keeps alternating between pending and
     * value results, and the Rust side stops polling when it drops the stream.
     * An implementation that is exhausted but keeps returning
     * {@link PollResult#pending()} without ever waking will stall the Rust
     * async runtime forever.
     *
     * <h4>Waker lifecycle</h4>
     * Each call may supply a <em>different</em> {@link Waker}, and only the most
     * recent one is able to re-schedule the task. Implementations MUST replace
     * any previously stored waker with the one passed here, and MUST invoke it
     * at most once (a {@link Waker} is single-use).
     *
     * @param waker the {@link Waker} to call when this {@link Stream} becomes ready
     * @return the result of the poll
     */
    PollResult<T> pollNext(Waker waker);
}