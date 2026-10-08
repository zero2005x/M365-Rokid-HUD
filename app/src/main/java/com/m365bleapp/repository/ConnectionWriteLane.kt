package com.m365bleapp.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes entire radio messages, including pacing and all fragments, on one captured link. */
internal class ConnectionWriteLane<H : Any>(private val resources: ConnectionResources<H>) {
    private val mutex = Mutex()

    suspend fun <R> write(expectedEpoch: Long, operation: suspend (H) -> R): R = mutex.withLock {
        val handle = requireNotNull(resources.boundHandle(expectedEpoch)) { "connection retired before write" }
        val result = operation(handle)
        check(resources.boundHandle(expectedEpoch) === handle) { "connection changed during write" }
        result
    }
}
