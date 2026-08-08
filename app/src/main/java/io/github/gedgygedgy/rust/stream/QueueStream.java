package io.github.gedgygedgy.rust.stream;

import io.github.gedgygedgy.rust.task.PollResult;
import io.github.gedgygedgy.rust.task.Waker;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Queue;

/**
 * A simple implementation of {@link Stream} that can be fed values by calling
 * {@link #add(Object)}.
 * <p>
 * This class is not thread-safe. It is intended to be used in a single-threaded
 * environment.
 * <p>
 * Null values are not permitted; see {@link #add(Object)}.
 *
 * @param <T> the type of the values that will be produced by this {@link Stream}
 */
public class QueueStream<T> implements Stream<T> {
    private final Queue<T> queue = new LinkedList<>();

    /**
     * The {@link Waker} handed to us by the most recent {@link #pollNext} call
     * that returned {@link PollResult#pending()}.
     * <p>
     * Always the <em>most recent</em> one: the runtime may poll with a
     * different {@link Waker} each time, and only the newest one is able to
     * re-schedule the task.
     */
    private Waker pendingWaker;

    @Override
    public PollResult<T> pollNext(Waker waker) {
        // Check emptiness explicitly rather than treating a null poll() result
        // as "empty": add() rejects nulls, but relying on the sentinel made the
        // invariant invisible and easy to break.
        if (queue.isEmpty()) {
            // Retain the waker so add() can wake the consumer. Previously the
            // waker was dropped, so a consumer that polled once, got Pending
            // and waited to be re-polled hung forever even after values arrived.
            this.pendingWaker = waker;
            return PollResult.pending();
        }

        this.pendingWaker = null;
        return PollResult.fromValue(queue.poll());
    }

    /**
     * Add a value to this {@link Stream}, waking the consumer if it is waiting.
     *
     * @param value the value to add, must not be null
     * @throws NullPointerException if {@code value} is null
     */
    public void add(T value) {
        Objects.requireNonNull(value, "QueueStream does not support null values");

        queue.add(value);

        // Wake on the empty -> non-empty transition. A Waker is single-use, so
        // clear it before calling.
        Waker waker = this.pendingWaker;
        if (waker != null) {
            this.pendingWaker = null;
            waker.wake();
        }
    }
}
