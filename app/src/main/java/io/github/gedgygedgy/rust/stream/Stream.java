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
 * In general, you will probably want to use...
 * @param <T> the type of the values that will be produced by this {@link Stream}
 */
public interface Stream<T> {
    /**
     * Attempt to get the next value from this {@link Stream}. This method will be called
     * repeatedly by the Rust runtime until the {@link Stream} is exhausted.
     * <p>
     * This method should not block. If the {@link Stream} is not ready, it
     * should return {@link PollResult#Pending} and arrange for the {@link Waker}
     * to be called when the {@link Stream} becomes ready.
     *
     * @param waker the {@link Waker} to call when this {@link Stream} becomes ready
     * @return the result of the poll
     */
    PollResult<T> pollNext(Waker waker);
}