package com.m365bleapp.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class ConnectionResourcesTest {
    @Test fun `stale handles and late login cannot attach to reconnect`() {
        val freed = mutableListOf<Long>()
        val resources = ConnectionResources<Any> { freed.add(it) }
        val oldEpoch = resources.epoch
        val old = Any()
        assertTrue(resources.attach(oldEpoch, old))
        assertTrue(resources.installNative(oldEpoch, 7))
        assertSame(old, resources.invalidate())
        val newHandle = Any()
        assertTrue(resources.attach(resources.epoch, newHandle))
        assertTrue(resources.installNative(resources.epoch, 8))
        assertFalse(resources.attach(oldEpoch, old))
        assertFalse(resources.installNative(oldEpoch, 9))
        assertNull(resources.boundHandle(oldEpoch))
        assertNull(resources.useNative(oldEpoch) { error("stale native operation") })
        assertSame(newHandle, resources.handle)
        assertEquals(8L, resources.useNative(resources.epoch) { it })
        assertEquals(listOf(7L, 9L), freed)
    }

    @Test fun `disconnect frees native handle once and rejects absent connection`() {
        val freed = mutableListOf<Long>()
        val resources = ConnectionResources<Any> { freed.add(it) }
        assertFalse(resources.hasNative)
        assertFalse(resources.installNative(resources.epoch, 0))
        assertFalse(resources.installNative(resources.epoch, 5))
        assertTrue(resources.attach(resources.epoch, Any()))
        assertFalse(resources.attach(resources.epoch, Any()))
        assertTrue(resources.installNative(resources.epoch, 6))
        assertTrue(resources.hasNative)
        assertTrue(resources.installNative(resources.epoch, 6))
        assertNotNull(resources.invalidate())
        assertNull(resources.invalidate())
        assertFalse(resources.hasNative)
        assertNull(resources.useNative(resources.epoch) { error("no native handle") })
        assertEquals(listOf(5L, 6L), freed)
    }

    @Test fun `replacing native session retires previous pointer`() {
        val freed = mutableListOf<Long>()
        val resources = ConnectionResources<Any> { freed.add(it) }
        assertTrue(resources.attach(resources.epoch, Any()))
        assertTrue(resources.installNative(resources.epoch, 1))
        assertTrue(resources.installNative(resources.epoch, 2))
        assertEquals(listOf(1L), freed)
        assertEquals(2L, resources.useNative(resources.epoch) { it })
    }

    @Test fun `native retirement waits for admitted crypto operation`() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val attemptingRetirement = CountDownLatch(1)
        val freed = CountDownLatch(1)
        val resources = ConnectionResources<Any> { freed.countDown() }
        assertTrue(resources.attach(resources.epoch, Any()))
        assertTrue(resources.installNative(resources.epoch, 7))
        val expected = resources.epoch
        val executor = Executors.newFixedThreadPool(2)
        try {
            val crypto = executor.submit<Long?> {
                resources.useNative(expected) {
                    entered.countDown()
                    check(finish.await(5, TimeUnit.SECONDS))
                    assertEquals(1L, freed.count)
                    it
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val retirement = executor.submit {
                attemptingRetirement.countDown()
                resources.invalidate()
            }
            assertTrue(attemptingRetirement.await(5, TimeUnit.SECONDS))
            assertFalse(freed.await(100, TimeUnit.MILLISECONDS))
            finish.countDown()
            assertEquals(7L, requireNotNull(crypto.get(5, TimeUnit.SECONDS)))
            retirement.get(5, TimeUnit.SECONDS)
            assertTrue(freed.await(5, TimeUnit.SECONDS))
            assertFalse(resources.isCurrent(expected))
        } finally {
            finish.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun `callback mutation and exceptions release ownership monitor`() {
        val resources = ConnectionResources<Any> { }
        val expected = resources.epoch
        assertEquals("accepted", resources.ifCurrent(expected) { "accepted" })
        try {
            resources.ifCurrent(expected) { throw IllegalStateException("synthetic") }
            fail("expected failure")
        } catch (_: IllegalStateException) { }
        resources.invalidate()
        assertNull(resources.ifCurrent(expected) { error("stale callback") })
        assertEquals("new", resources.ifCurrent(resources.epoch) { "new" })
        assertEquals(resources.epoch, resources.withCurrent { it })
    }

    @Test fun `epoch context survives a derived coroutine context`() {
        val epoch = ConnectionEpoch(3)
        val context = kotlin.coroutines.EmptyCoroutineContext + epoch
        assertSame(epoch, context[ConnectionEpoch])
        assertEquals(3L, context[ConnectionEpoch]?.value)
        assertSame(epoch, ConnectionEpoch.forEntry(context, ConnectionEpoch(4), 4))
        val current = ConnectionEpoch(4)
        assertSame(current, ConnectionEpoch.forEntry(kotlin.coroutines.EmptyCoroutineContext, current, 4))
        assertEquals(5L, ConnectionEpoch.forEntry(kotlin.coroutines.EmptyCoroutineContext, null, 5).value)
    }

    @Test fun `retired mailbox cannot consume replacement connection bytes`() {
        val old = ConnectionEpoch(1, "device-a")
        val current = ConnectionEpoch(2, "device-b")
        old.closeMailboxes()
        assertFalse(old.control.trySend(byteArrayOf(1)).isSuccess)
        assertFalse(old.uart.trySend(byteArrayOf(1)).isSuccess)
        assertTrue(current.uart.trySend(byteArrayOf(2)).isSuccess)
        assertTrue(current.control.trySend(byteArrayOf(3)).isSuccess)
        assertTrue(old.uart.tryReceive().isClosed)
        assertEquals(2, current.uart.tryReceive().getOrThrow()[0].toInt())
        assertEquals(3, current.control.tryReceive().getOrThrow()[0].toInt())
    }
}
