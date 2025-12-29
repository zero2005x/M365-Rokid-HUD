package io.github.gedgygedgy.rust.task;

/**
 * A waker that can be used to wake a task that is waiting on a
 * {@link io.github.gedgygedgy.rust.future.Future} or
 * {@link io.github.gedgygedgy.rust.stream.Stream}.
 */
public interface Waker {
    /**
     * Wake the task.
     */
    void wake();
}