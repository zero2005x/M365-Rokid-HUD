package io.github.gedgygedgy.rust.future;

import io.github.gedgygedgy.rust.task.PollResult;
import io.github.gedgygedgy.rust.task.Waker;

/**
 * Interface for allowing Rust code to interact with Java code in an
 * asynchronous manner. The intention of this interface is for asynchronous
 * Rust code to directly call Java code that returns a {@link Future}, and
 * then poll the {@link Future} using a {@code jni_utils::future::JFuture}.
 * In this way, the complexities of interacting with asynchronous Java code can
 * be abstracted into a simple {@code jni_utils::future::JFuture} that Rust
 * code can {@code await} on.
 * <p>
 * In general, you will probably want to use {@link SimpleFuture} rather than
 * implementing this interface directly: it already implements the waker
 * contract described below.
 *
 * @param <T> the type of the value that will be produced by this {@link Future}
 */
public interface Future<T> {
    /**
     * Attempt to resolve this {@link Future}. This method will be called
     * repeatedly by the Rust runtime until the {@link Future} is ready.
     * <p>
     * This method should not block. If the {@link Future} is not ready, it
     * should return {@link PollResult#pending()} and arrange for the
     * {@link Waker} to be called when the {@link Future} becomes ready.
     *
     * <h4>Waker lifecycle</h4>
     * <ol>
     *   <li>This method may be called repeatedly with a <em>different</em>
     *       {@link Waker} each time. Only the most recent one is the one the
     *       runtime will actually use to re-schedule the task, so an
     *       implementation MUST replace any previously stored waker with the
     *       one passed here. Storing only the first waker and continuing to
     *       return pending loses the wakeup and hangs the Rust runtime.</li>
     *   <li>A {@link Waker} is single-use: invoke it at most once, when the
     *       {@link Future} becomes ready, and clear the stored reference before
     *       calling it.</li>
     * </ol>
     *
     * @param waker the {@link Waker} to call when this {@link Future} becomes ready
     * @return the result of the poll
     */
    PollResult<T> poll(Waker waker);
}