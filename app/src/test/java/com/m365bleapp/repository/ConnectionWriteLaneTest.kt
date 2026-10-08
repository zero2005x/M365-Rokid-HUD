package com.m365bleapp.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConnectionWriteLaneTest {
    @Test fun `fragments and pacing hold lane until entire message ends`() = runBlocking {
        val resources = ConnectionResources<Any> { }
        val handle = Any()
        resources.attach(resources.epoch, handle)
        val lane = ConnectionWriteLane(resources)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            lane.write(resources.epoch) {
                assertSame(handle, it)
                events.add("first fragment")
                entered.complete(Unit)
                finish.await()
                events.add("last fragment")
            }
        }
        entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            lane.write(resources.epoch) { events.add("next message") }
        }
        assertEquals(listOf("first fragment"), events)
        finish.complete(Unit)
        first.await(); second.await()
        assertEquals(listOf("first fragment", "last fragment", "next message"), events)
    }

    @Test fun `queued work never migrates to new handle and lane releases after rejection`() = runBlocking {
        val resources = ConnectionResources<Any> { }
        val epoch = resources.epoch
        resources.attach(epoch, Any())
        val lane = ConnectionWriteLane(resources)
        val finish = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { lane.write(epoch) { finish.await() } }
        }
        val oldWaiter = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { lane.write(epoch) { fail("old waiter must not use new connection") } }
        }
        resources.invalidate()
        val current = Any()
        resources.attach(resources.epoch, current)
        finish.complete(Unit)
        assertTrue(first.await().exceptionOrNull() is IllegalStateException)
        assertTrue(oldWaiter.await().exceptionOrNull() is IllegalArgumentException)
        assertSame(current, lane.write(resources.epoch) { it })
    }

    @Test fun `failed operation releases lane for subsequent message`() = runBlocking {
        val resources = ConnectionResources<Any> { }
        resources.attach(resources.epoch, Any())
        val lane = ConnectionWriteLane(resources)
        val result = runCatching { lane.write(resources.epoch) { throw IllegalStateException("synthetic") } }
        assertTrue(result.isFailure)
        assertEquals("next", lane.write(resources.epoch) { "next" })
    }
}
