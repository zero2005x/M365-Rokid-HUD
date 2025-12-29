package io.github.gedgygedgy.rust.stream;

import io.github.gedgygedgy.rust.task.PollResult;
import io.github.gedgygedgy.rust.task.Waker;
import java.util.LinkedList;
import java.util.Queue;

/**
 * A simple implementation of {@link Stream} that can be fed values by calling
 * {@link #add(Object)}.
 * <p>
 * This class is not thread-safe. It is intended to be used in a single-threaded
 * environment.
 *
 * @param <T> the type of the values that will be produced by this {@link Stream}
 */
public class QueueStream<T> implements Stream<T> {
    private final Queue<T> queue = new LinkedList<>();

    @Override
    public PollResult<T> pollNext(Waker waker) {
        T value = queue.poll();
        if (value != null) {
            return PollResult.fromValue(value);
        } else {
            return PollResult.pending();
        }
    }

    /**
     * Add a value to this {@link Stream}.
     *
     * @param value the value to add
     */
    public void add(T value) {
        queue.add(value);
    }
}