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
 * In general, you will probably want to use...
 * @param <T> the type of the value that will be produced by this {@link Future}
 */
public interface Future<T> {
    /**
     * Attempt to resolve this {@link Future}. This method will be called
     * repeatedly by the Rust runtime until the {@link Future} is ready.
     * <p>
     * This method should not block. If the {@link Future} is not ready, it
     * should return {@link PollResult#Pending} and arrange for the {@link Waker}
     * to be called when the {@link Future} becomes ready.
     *
     * @param waker the {@link Waker} to call when this {@link Future} becomes ready
     * @return the result of the poll
     */
    PollResult<T> poll(Waker waker);
}