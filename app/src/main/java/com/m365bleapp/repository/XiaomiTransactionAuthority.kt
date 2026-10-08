// SPDX-License-Identifier: MIT
package com.m365bleapp.repository

import io.github.zero2005x.pev.core.codec.xiaomi.XiaomiPdu
import io.github.zero2005x.pev.core.codec.xiaomi.XiaomiReply
import io.github.zero2005x.pev.core.command.CommandCoordinator
import io.github.zero2005x.pev.core.command.CommandExecutionContext
import io.github.zero2005x.pev.core.command.CommandOutcome
import io.github.zero2005x.pev.core.command.CommandPlan
import io.github.zero2005x.pev.core.command.CommandResult
import java.util.concurrent.locks.ReentrantLock

/** One authority for entire read transactions and typed setting plans, including RMW/readback. */
internal class XiaomiTransactionAuthority(private val transport: XiaomiEncryptedTransport) {
    private val transactions = ReentrantLock()
    private val coordinator = CommandCoordinator(transport)

    fun read(register: Int, length: Int, timeoutMs: Long = 5000): ByteArray? = transaction {
        query(register, length, timeoutMs)
    }

    /** Builder runs inside the same transaction as any fresh observation and the complete plan. */
    fun execute(build: (read: (Int, Int) -> ByteArray?) -> CommandPlan?, context: (CommandPlan) -> CommandExecutionContext): CommandResult = transaction {
        val plan = build { register, length -> query(register, length, 1000) }
            ?: return@transaction CommandResult(CommandOutcome.REJECTED, "typed plan prerequisites unavailable")
        val before = transport.submissionSequence
        val result = coordinator.execute(plan, context(plan))
        val confirmed = result.outcome in setOf(CommandOutcome.READBACK_CONFIRMED, CommandOutcome.ACK_CONFIRMED)
        if (!confirmed && transport.submissionSequence != before) transport.poison()
        result
    }

    private fun query(register: Int, length: Int, timeoutMs: Long): ByteArray? {
        require(register in 0..255 && length in 1..255 && timeoutMs in 1..30_000)
        val cursor = transport.notificationSequence
        if (!transport.writeForConnection(XiaomiPdu.read(register, length), transport.connectionId)) return null
        val start = System.nanoTime()
        var after = cursor
        while (transport.connected) {
            val remaining = timeoutMs - (System.nanoTime() - start) / 1_000_000
            if (remaining <= 0) break
            val reply = transport.awaitNotifyAfter(remaining, after) ?: break
            if ((System.nanoTime() - start) / 1_000_000 >= timeoutMs) break
            after = reply.sequence
            val decoded = XiaomiReply.parse(reply.bytes)
            if (reply.connectionId == transport.connectionId && decoded?.direction == 0x23 &&
                decoded.type == XiaomiPdu.CMD_READ && decoded.register == register && decoded.data.size == length) return reply.bytes
        }
        // There is no request ID. A late reply after an unanswered query must not confirm a
        // subsequent same-register write/readback; reconnect rather than reuse this epoch.
        transport.poison()
        return null
    }

    private fun <T> transaction(operation: () -> T): T {
        transactions.lockInterruptibly()
        try {
            return operation()
        } catch (failure: Exception) {
            transport.poison()
            throw failure
        } finally {
            transactions.unlock()
        }
    }

    fun close() { coordinator.cancel(); transport.close() }
}
