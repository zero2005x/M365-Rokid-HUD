package com.m365bleapp.repository

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.channels.Channel

/** Carries the connection attempt through nested handshake and polling coroutines. */
internal class ConnectionEpoch(val value: Long, val deviceId: String = "") : AbstractCoroutineContextElement(Key) {
    val control = Channel<ByteArray>(64)
    val uart = Channel<ByteArray>(64)

    fun closeMailboxes() { control.close(); uart.close() }

    companion object Key : CoroutineContext.Key<ConnectionEpoch> {
        /** Nested commands keep their inherited binding; only a true entry captures the live link. */
        fun forEntry(context: CoroutineContext, current: ConnectionEpoch?, fallbackEpoch: Long): ConnectionEpoch =
            context[Key] ?: current ?: ConnectionEpoch(fallbackEpoch)
    }
}

/**
 * Owns a native session and its GATT handle. Native use and retirement share the same monitor:
 * a disconnect cannot free a pointer while encryption/decryption is using it. Radio waits must
 * run outside this monitor, using the captured handle rather than resolving the next connection.
 */
internal class ConnectionResources<H : Any>(private val freeNative: (Long) -> Unit) {
    private val monitor = Any()
    @Volatile var epoch: Long = 0
        private set
    @Volatile var handle: H? = null
        private set
    private var nativeHandle = 0L

    val hasNative: Boolean get() = synchronized(monitor) { nativeHandle != 0L }

    fun isCurrent(expected: Long): Boolean = epoch == expected

    fun attach(expected: Long, candidate: H): Boolean = synchronized(monitor) {
        if (epoch != expected || handle != null) false
        else { handle = candidate; true }
    }

    fun boundHandle(expected: Long): H? = synchronized(monitor) {
        if (epoch == expected) handle else null
    }

    /** A late login still owns its newly allocated pointer; retire it on rejected installation. */
    fun installNative(expected: Long, candidate: Long): Boolean = synchronized(monitor) {
        if (candidate == 0L) return@synchronized false
        if (epoch != expected || handle == null) {
            freeNative(candidate)
            return@synchronized false
        }
        if (nativeHandle != candidate) {
            val previous = nativeHandle
            nativeHandle = candidate
            if (previous != 0L) freeNative(previous)
        }
        true
    }

    fun <R> useNative(expected: Long, operation: (Long) -> R): R? = synchronized(monitor) {
        if (epoch != expected || nativeHandle == 0L) null else operation(nativeHandle)
    }

    fun <R> ifCurrent(expected: Long, operation: () -> R): R? = synchronized(monitor) {
        if (epoch == expected) operation() else null
    }

    /** Register a pending operation atomically with the current generation. */
    fun <R> withCurrent(operation: (Long) -> R): R = synchronized(monitor) { operation(epoch) }

    /** Invalidate first; callbacks and late login results cannot revive this attempt. */
    fun invalidate(): H? = synchronized(monitor) {
        epoch++
        val previousHandle = handle
        handle = null
        val previousNative = nativeHandle
        nativeHandle = 0L
        if (previousNative != 0L) freeNative(previousNative)
        previousHandle
    }
}
