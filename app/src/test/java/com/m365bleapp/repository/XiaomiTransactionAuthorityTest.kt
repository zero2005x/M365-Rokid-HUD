// SPDX-License-Identifier: MIT
package com.m365bleapp.repository

import io.github.zero2005x.pev.core.codec.xiaomi.StatusWordWriteOrder
import io.github.zero2005x.pev.core.command.CommandOutcome
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class XiaomiTransactionAuthorityTest {
    @Test fun `read ignores unrelated direction type register and length`() {
        val link = SyntheticXiaomiPhoneLink()
        link.onSubmit = {
            link.emit(SyntheticXiaomiPhoneLink.reply(0x7D, bytes(2, 0), direction = 0x22))
            link.emit(SyntheticXiaomiPhoneLink.reply(0x7D, bytes(2, 0), type = 2))
            link.emit(SyntheticXiaomiPhoneLink.reply(0x7C, bytes(2, 0)))
            link.emit(SyntheticXiaomiPhoneLink.reply(0x7D, bytes(2)))
            link.emit(SyntheticXiaomiPhoneLink.reply(0x7D, bytes(0x93, 0xA5)))
            true
        }
        assertArrayEquals(SyntheticXiaomiPhoneLink.reply(0x7D, bytes(0x93, 0xA5)),
            link.session.authority.read(0x7D, 2, 100))
        assertEquals(1, link.writes.size)
        assertEquals(0, link.poisonCount)
        link.session.close()
    }

    @Test fun `pre request buffered reply cannot satisfy read`() {
        val link = SyntheticXiaomiPhoneLink()
        link.emit(SyntheticXiaomiPhoneLink.reply(0x7D, bytes(1, 0)))
        link.onSubmit = { link.emit(SyntheticXiaomiPhoneLink.reply(0x7D, bytes(2, 0))); true }
        assertArrayEquals(SyntheticXiaomiPhoneLink.reply(0x7D, bytes(2, 0)),
            link.session.authority.read(0x7D, 2, 100))
        assertEquals(0, link.poisonCount)
        link.session.close()
    }

    @Test fun `fragment starting before read cursor cannot become fresh confirmation`() {
        val link = SyntheticXiaomiPhoneLink()
        val old = SyntheticXiaomiPhoneLink.envelope(SyntheticXiaomiPhoneLink.reply(0x7D, bytes(1, 0)))
        link.transport.accept(old.copyOfRange(0, 5))
        link.onSubmit = {
            link.transport.accept(old.copyOfRange(5, old.size))
            link.emit(SyntheticXiaomiPhoneLink.reply(0x7D, bytes(2, 0)))
            true
        }
        assertArrayEquals(SyntheticXiaomiPhoneLink.reply(0x7D, bytes(2, 0)),
            link.session.authority.read(0x7D, 2, 100))
        link.session.close()
    }

    @Test fun `unanswered request poisons epoch so late reply cannot confirm next read`() {
        val link = SyntheticXiaomiPhoneLink()
        link.onSubmit = { true }
        assertNull(link.session.authority.read(0x7D, 2, 20))
        assertEquals(1, link.poisonCount)
        link.emit(SyntheticXiaomiPhoneLink.reply(0x7D, bytes(2, 0)))
        assertNull(link.session.authority.read(0x7D, 2, 20))
        assertEquals(1, link.writes.size)
        assertFalse(link.transport.connected)
        link.session.close()
    }

    @Test fun `submission exception retires epoch before any next request`() {
        val link = SyntheticXiaomiPhoneLink()
        link.onSubmit = { throw IllegalStateException("synthetic failure after ambiguous submission") }
        try {
            link.session.authority.read(0x7D, 2, 20)
            fail("submission exception must propagate")
        } catch (expected: IllegalStateException) {
            assertEquals("synthetic failure after ambiguous submission", expected.message)
        }
        assertFalse(link.transport.connected)
        assertEquals(1, link.poisonCount)
        assertNull(link.session.authority.read(0x7D, 2, 20))
        assertEquals(1, link.writes.size)
        link.session.close()
    }

    @Test fun `poll retains authority until matched reply then complete RMW precedes next poll`() {
        val link = SyntheticXiaomiPhoneLink()
        link.session.enableM365Experimental()
        link.words[0x7D] = 0xA591
        val initialPoll = CountDownLatch(1)
        val settingReadback = CountDownLatch(1)
        val settingStarted = CountDownLatch(1)
        val lastPollStarted = CountDownLatch(1)
        val originalSubmit = link.onSubmit
        link.onSubmit = { request ->
            val register = request[3].toInt() and 0xFF
            when {
                register == 0x3A -> { initialPoll.countDown(); true }
                register == 0x7D && request[2].toInt() == 1 && link.words[0x7D] == 0xA593 -> {
                    settingReadback.countDown(); true
                }
                else -> originalSubmit(request)
            }
        }
        val pollResult = AtomicReference<ByteArray?>()
        val settingOutcome = AtomicReference<CommandOutcome>()
        val failures = CollectionsForAuthorityTest()
        val first = thread(name = "synthetic-poll") {
            failures.capture { pollResult.set(link.session.authority.read(0x3A, 4, 5_000)) }
        }
        var settings: Thread? = null
        var last: Thread? = null
        try {
            assertTrue(initialPoll.await(2, TimeUnit.SECONDS))
            settings = thread(name = "synthetic-setting") {
                settingStarted.countDown()
                failures.capture { settingOutcome.set(link.session.execute(
                    XiaomiSetting.TailLight(true, StatusWordWriteOrder.LITTLE_ENDIAN)).outcome) }
            }
            assertTrue(settingStarted.await(2, TimeUnit.SECONDS))
            awaitBlocked(settings)
            assertEquals(1, link.writes.size)
            link.emit(SyntheticXiaomiPhoneLink.reply(0x3A, bytes(5, 0, 9, 0)))
            assertTrue(settingReadback.await(2, TimeUnit.SECONDS))
            last = thread(name = "synthetic-next-poll") {
                lastPollStarted.countDown()
                failures.capture { link.session.authority.read(0x25, 2, 1_000) }
            }
            assertTrue(lastPollStarted.await(2, TimeUnit.SECONDS))
            awaitBlocked(last)
            assertEquals(listOf(0x3A, 0x7D, 0x7D, 0x7D), link.writes.map { it[3].toInt() and 0xFF })
            link.emit(SyntheticXiaomiPhoneLink.reply(0x7D, bytes(0x93, 0xA5)))
            first.join(2_000); settings.join(2_000); last.join(2_000)
            assertFalse(first.isAlive); assertFalse(settings.isAlive); assertFalse(last.isAlive)
            failures.assertNone()
            assertNotNull(pollResult.get())
            assertEquals(CommandOutcome.READBACK_CONFIRMED, settingOutcome.get())
            assertEquals(listOf(0x3A, 0x7D, 0x7D, 0x7D, 0x25), link.writes.map { it[3].toInt() and 0xFF })
        } finally {
            link.session.close()
            listOfNotNull(first, settings, last).forEach { it.interrupt(); it.join(2_000) }
        }
    }

    @Test fun `interrupted in flight reply waiter poisons epoch and releases authority`() {
        val link = SyntheticXiaomiPhoneLink()
        val submitted = CountDownLatch(1)
        link.onSubmit = { submitted.countDown(); true }
        val failure = AtomicReference<Throwable?>()
        val worker = thread(name = "synthetic-cancelled-read") {
            try { link.session.authority.read(0x7D, 2, 5_000) } catch (caught: Throwable) { failure.set(caught) }
        }
        try {
            assertTrue(submitted.await(2, TimeUnit.SECONDS))
            worker.interrupt(); worker.join(2_000)
            assertFalse(worker.isAlive)
            assertTrue(failure.get() is InterruptedException)
            assertEquals(1, link.poisonCount)
            assertNull(link.session.authority.read(0x7D, 2, 20))
            assertEquals(1, link.writes.size)
        } finally { link.session.close(); worker.interrupt(); worker.join(2_000) }
    }

    @Test fun `interrupted queued caller never sends and does not poison active read`() {
        val link = SyntheticXiaomiPhoneLink()
        val submitted = CountDownLatch(1)
        val queued = CountDownLatch(1)
        link.onSubmit = { submitted.countDown(); true }
        val activeFailure = AtomicReference<Throwable?>()
        val queuedFailure = AtomicReference<Throwable?>()
        val active = thread(name = "synthetic-active-read") {
            try { link.session.authority.read(0x7D, 2, 5_000) } catch (caught: Throwable) { activeFailure.set(caught) }
        }
        var waiter: Thread? = null
        try {
            assertTrue(submitted.await(2, TimeUnit.SECONDS))
            waiter = thread(name = "synthetic-queued-read") {
                queued.countDown()
                try { link.session.authority.read(0x7C, 2, 5_000) } catch (caught: Throwable) { queuedFailure.set(caught) }
            }
            assertTrue(queued.await(2, TimeUnit.SECONDS))
            awaitBlocked(waiter)
            waiter.interrupt(); waiter.join(2_000)
            assertFalse(waiter.isAlive)
            assertTrue(queuedFailure.get() is InterruptedException)
            assertEquals(0, link.poisonCount)
            assertTrue(link.transport.connected)
            link.emit(SyntheticXiaomiPhoneLink.reply(0x7D, bytes(2, 0)))
            active.join(2_000)
            assertFalse(active.isAlive)
            assertNull(activeFailure.get())
            assertEquals(1, link.writes.size)
        } finally {
            link.session.close()
            listOfNotNull(active, waiter).forEach { it.interrupt(); it.join(2_000) }
        }
    }

    @Test fun `close wakes outstanding reply wait without waiting for transaction lock`() {
        val link = SyntheticXiaomiPhoneLink()
        val submitted = CountDownLatch(1)
        link.onSubmit = { submitted.countDown(); true }
        val failure = AtomicReference<Throwable?>()
        val worker = thread(name = "synthetic-closed-read") {
            try { link.session.authority.read(0x7D, 2, 5_000) } catch (caught: Throwable) { failure.set(caught) }
        }
        try {
            assertTrue(submitted.await(2, TimeUnit.SECONDS))
            link.session.authority.close()
            worker.join(2_000)
            assertFalse(worker.isAlive)
            assertNull(failure.get())
            assertNull(link.session.authority.read(0x7D, 2, 20))
            assertEquals(1, link.writes.size)
        } finally { link.session.close(); worker.interrupt(); worker.join(2_000) }
    }

    private fun awaitBlocked(worker: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (worker.state !in setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING)) {
            check(worker.isAlive) { "worker ended before reaching transaction wait" }
            check(System.nanoTime() < deadline) { "worker did not enter bounded wait" }
            Thread.yield()
        }
    }

    private fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()

    private class CollectionsForAuthorityTest {
        private val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        fun capture(block: () -> Unit) { try { block() } catch (failure: Throwable) { failures.add(failure) } }
        fun assertNone() { assertTrue(failures.joinToString { it.toString() }, failures.isEmpty()) }
    }
}
