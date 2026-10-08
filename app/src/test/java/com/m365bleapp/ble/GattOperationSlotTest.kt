// SPDX-License-Identifier: MIT
package com.m365bleapp.ble

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GattOperationSlotTest {
    private data class EqualValue(val value: Int)

    @Test fun `completion matches epoch and target identity without clearing a mismatch`() {
        val slot = GattOperationSlot<EqualValue, Any>()
        val target = EqualValue(1)
        val equalTarget = EqualValue(1)
        val continuation = Any()
        assertTrue(slot.register(4, target, continuation))
        assertNull(slot.complete(3, target))
        assertNull(slot.complete(4, equalTarget))
        assertTrue(slot.busy)
        assertSame(continuation, slot.complete(4, target))
        assertFalse(slot.busy)
        assertNull(slot.complete(4, target))
    }

    @Test fun `busy slot rejects registration without replacing admitted operation`() {
        val slot = GattOperationSlot<Any, Any>()
        val target = Any()
        val continuation = Any()
        assertFalse(slot.busy)
        assertTrue(slot.register(1, target, continuation))
        assertFalse(slot.register(2, Any(), Any()))
        assertSame(continuation, slot.complete(1, target))
    }

    @Test fun `cancellation requires epoch and continuation identity`() {
        val slot = GattOperationSlot<Any, EqualValue>()
        val continuation = EqualValue(1)
        assertTrue(slot.register(7, Any(), continuation))
        assertFalse(slot.cancel(6, continuation))
        assertFalse(slot.cancel(7, EqualValue(1)))
        assertTrue(slot.busy)
        assertTrue(slot.cancel(7, continuation))
        assertFalse(slot.busy)
        assertFalse(slot.cancel(7, continuation))
    }

    @Test fun `retirement returns admitted continuation once and releases slot`() {
        val slot = GattOperationSlot<Any, Any>()
        val target = Any()
        val continuation = Any()
        assertNull(slot.retire())
        assertTrue(slot.register(1, target, continuation))
        assertSame(continuation, slot.retire())
        assertFalse(slot.busy)
        assertNull(slot.complete(1, target))
        assertNull(slot.retire())
        val replacement = Any()
        assertTrue(slot.register(2, target, replacement))
        assertNull(slot.complete(1, target))
        assertSame(replacement, slot.complete(2, target))
    }

    @Test fun `correct completion releases slot for the next stack operation`() {
        val slot = GattOperationSlot<Any, Any>()
        val target = Any()
        val first = Any()
        val second = Any()
        assertTrue(slot.register(1, target, first))
        assertSame(first, slot.complete(1, target))
        assertTrue(slot.register(1, target, second))
        assertSame(second, slot.complete(1, target))
        assertFalse(slot.busy)
    }

    @Test fun `simultaneous registration has exactly one winner`() {
        val contenderCount = 8
        val slot = GattOperationSlot<Any, Any>()
        val ready = CountDownLatch(contenderCount)
        val start = CountDownLatch(1)
        val targets = List(contenderCount) { Any() }
        val continuations = List(contenderCount) { Any() }
        val executor = Executors.newFixedThreadPool(contenderCount)
        try {
            val attempts = targets.indices.map { index ->
                executor.submit<Boolean> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    slot.register(3, targets[index], continuations[index])
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val winners = attempts.indices.filter { attempts[it].get(5, TimeUnit.SECONDS) }
            assertEquals(1, winners.size)
            assertTrue(slot.busy)
            val winner = winners.single()
            assertSame(continuations[winner], slot.complete(3, targets[winner]))
            assertFalse(slot.busy)
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }
}
