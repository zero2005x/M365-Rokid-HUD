package com.m365hud.glass

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class BleSessionOwnerTest {
    @Test fun equalHandlesCannotPublishIntoAnotherConnection() {
        val sessions = BleSessionOwner<String>()
        val old = String(charArrayOf('a'))
        val next = String(charArrayOf('a'))
        sessions.replace(next)
        var fresh = false
        assertFalse(sessions.ifCurrent(old) { fresh = true })
        assertFalse(fresh)
        assertTrue(sessions.ifCurrent(next) { fresh = true })
        assertTrue(fresh)
    }

    @Test fun disconnectInvalidatesPendingWorkEvenWithoutANewHandle() {
        val sessions = BleSessionOwner<Any>()
        val old = Any()
        sessions.replace(old)
        sessions.replace(null)
        val scheduledReconnect = sessions.revision
        sessions.replace(null) // An explicit later disconnect cancels that intent.
        assertNotEquals(scheduledReconnect, sessions.revision)
        assertNull(sessions.current)
        assertFalse(sessions.ifCurrent(old) { fail("old callback admitted") })
    }

    @Test fun replacementWaitsForAdmittedCallbackThenRejectsLateDelivery() {
        val sessions = BleSessionOwner<Any>()
        val old = Any()
        val next = Any()
        sessions.replace(old)
        val admitted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val replacing = CountDownLatch(1)
        val replaced = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        var displayedBy: Any? = null
        val callback = Thread {
            try {
                assertTrue(sessions.ifCurrent(old) {
                    admitted.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    displayedBy = old
                })
            } catch (error: Throwable) { failure.set(error) }
        }
        val reconnect = Thread {
            try {
                replacing.countDown()
                sessions.locked {
                    sessions.replace(next)
                    displayedBy = null
                }
                replaced.countDown()
            } catch (error: Throwable) { failure.set(error) }
        }
        try {
            callback.start()
            assertTrue(admitted.await(5, TimeUnit.SECONDS))
            reconnect.start()
            assertTrue(replacing.await(5, TimeUnit.SECONDS))
            assertFalse(replaced.await(100, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            callback.join(5_000)
            reconnect.join(5_000)
        }
        assertFalse(callback.isAlive)
        assertFalse(reconnect.isAlive)
        failure.get()?.let { throw it }
        assertSame(next, sessions.current)
        assertNull(displayedBy)
        assertFalse(sessions.ifCurrent(old) { displayedBy = old })
        assertNull(displayedBy)
    }

    @Test fun failingCallbackReleasesOwnershipForTeardown() {
        val sessions = BleSessionOwner<Any>()
        val old = Any()
        sessions.replace(old)
        assertThrows(IllegalStateException::class.java) {
            sessions.ifCurrent(old) { error("submission rejected") }
        }
        sessions.replace(null)
        assertFalse(sessions.ifCurrent(old) { fail("teardown did not invalidate owner") })
    }
}
