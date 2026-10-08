// SPDX-License-Identifier: MIT
package com.m365bleapp.ble

/**
 * Holds one stack operation until a callback for its epoch and exact target arrives.
 *
 * Android callbacks carry no operation ID. Callers must therefore serialize ALL writes,
 * including WRITE_NO_RESPONSE, through the stack callback. Cancellation or timeout of a
 * pending operation must retire the connection before registering another operation:
 * identity matching alone cannot distinguish a late callback for the same target.
 */
internal class GattOperationSlot<T : Any, C : Any> {
    private class Pending<T : Any, C : Any>(
        val epoch: Long,
        val target: T,
        val continuation: C,
    )

    private val monitor = Any()
    private var pending: Pending<T, C>? = null

    val busy: Boolean get() = synchronized(monitor) { pending != null }

    fun register(epoch: Long, target: T, continuation: C): Boolean = synchronized(monitor) {
        if (pending != null) false
        else {
            pending = Pending(epoch, target, continuation)
            true
        }
    }

    fun complete(epoch: Long, target: T): C? = synchronized(monitor) {
        val operation = pending
        if (operation == null || operation.epoch != epoch || operation.target !== target) null
        else {
            pending = null
            operation.continuation
        }
    }

    fun cancel(epoch: Long, continuation: C): Boolean = synchronized(monitor) {
        val operation = pending
        if (operation == null || operation.epoch != epoch || operation.continuation !== continuation) false
        else {
            pending = null
            true
        }
    }

    fun retire(): C? = synchronized(monitor) {
        val continuation = pending?.continuation
        pending = null
        continuation
    }
}
