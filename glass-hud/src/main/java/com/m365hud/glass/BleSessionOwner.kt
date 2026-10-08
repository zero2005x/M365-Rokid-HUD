package com.m365hud.glass

/**
 * Serializes a connection transition with the callbacks admitted for that connection.
 * Blocks must be short and non-suspending; a GATT submission is not a vehicle acknowledgment.
 * Reference identity matters even when two handles compare equal. Revision also fences delayed
 * reconnect work while there is no current handle.
 */
internal class BleSessionOwner<T : Any> {
    private val lock = Any()
    @Volatile var current: T? = null
        private set
    @Volatile var revision: Long = 0
        private set

    fun <R> locked(action: () -> R): R = synchronized(lock) { action() }

    fun replace(next: T?) = locked {
        current = next
        revision++
    }

    fun ifCurrent(expected: T, action: () -> Unit): Boolean = locked {
        if (current !== expected) return@locked false
        action()
        true
    }
}
