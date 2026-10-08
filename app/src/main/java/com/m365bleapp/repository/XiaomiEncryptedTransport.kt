// SPDX-License-Identifier: MIT
package com.m365bleapp.repository

import io.github.zero2005x.pev.core.codec.xiaomi.XiaomiPdu
import io.github.zero2005x.pev.core.codec.xiaomi.XiaomiReply
import io.github.zero2005x.pev.core.transport.PevTransport
import io.github.zero2005x.pev.core.transport.TransportNotification
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/** Fixed login/GATT binding. The injected writer must submit once, without retries. */
internal class XiaomiEncryptedTransport(
    override val deviceId: String,
    override val connectionId: String,
    private val live: () -> Boolean,
    private val currentMtu: () -> Int,
    private val encrypt: (ByteArray) -> ByteArray?,
    private val submit: (ByteArray) -> Boolean,
    private val decrypt: (ByteArray) -> ByteArray?,
    private val onReply: (ByteArray, Long) -> Unit,
    private val onPoison: () -> Unit,
    private val nowMs: () -> Long,
    private val maxReplies: Int = 64,
    private val maxReplyBytes: Int = 8192,
) : PevTransport {
    private data class Ingress(val value: Byte, val sequence: Long, val atMs: Long)
    private data class Reply(val notification: TransportNotification, val atMs: Long)
    private val monitor = Object()
    private val ingress = ArrayDeque<Ingress>()
    private val replies = ArrayDeque<Reply>()
    private val cursor = AtomicLong()
    private val submissions = AtomicLong()
    private var replyBytes = 0
    @Volatile private var closed = false
    @Volatile var lastReplyAtMs: Long? = null
        private set

    init { require(maxReplies > 0 && maxReplyBytes >= 260) }

    override val mtu: Int get() = currentMtu()
    override val connected: Boolean get() = !closed && live()
    override val notificationSequence: Long get() = cursor.get()
    val submissionSequence: Long get() = submissions.get()

    override fun write(bytes: ByteArray): Boolean = writeForConnection(bytes, connectionId)

    override fun writeForConnection(bytes: ByteArray, expectedConnectionId: String): Boolean {
        if (!connected || expectedConnectionId != connectionId || !logicalPdu(bytes)) return false
        val encrypted = try { encrypt(bytes.copyOf()) }
            catch (failure: Exception) { poison(); throw failure }
        if (encrypted == null || encrypted.isEmpty() || !connected) return false
        submissions.incrementAndGet()
        val accepted = try { submit(encrypted) }
            catch (failure: Exception) { poison(); throw failure }
        if (!accepted) { poison(); return false }
        return connected
    }

    private fun logicalPdu(bytes: ByteArray): Boolean = bytes.size in 5..257 &&
        (bytes[0].toInt() and 0xFF) == bytes.size - 2 &&
        (bytes[1].toInt() and 0xFF) in setOf(XiaomiPdu.ADDR_ESC, XiaomiPdu.ADDR_BMS) &&
        (bytes[2].toInt() and 0xFF) in setOf(XiaomiPdu.CMD_READ, XiaomiPdu.CMD_WRITE)

    /**
     * Called by the captured UART callback, independently of transaction execution. Cursor is
     * a byte ingress position: a split frame retains its FIRST byte's cursor and timestamp.
     * Buffered pre-write fragments therefore cannot be promoted into fresh confirmations.
     * Framing follows this repository's MIT Rust 55 AB / len+16 / complement-checksum envelope.
     * Native AES-CCM authentication remains mandatory; this is not an alternate crypto codec.
     */
    fun accept(bytes: ByteArray) {
        if (!connected) return
        if (bytes.size > 4096 || cursor.get() > Long.MAX_VALUE - bytes.size) { poison(); return }
        val at = nowMs()
        val start = cursor.getAndAdd(bytes.size.toLong())
        val frames = synchronized(monitor) {
            if (closed) return
            val output = mutableListOf<Pair<Ingress, ByteArray>>()
            bytes.forEachIndexed { index, byte ->
                ingress.addLast(Ingress(byte, start + index + 1, at))
                extract(output)
            }
            output
        }
        // No buffer monitor is held while invoking native code, application state or retirement.
        for ((first, frame) in frames) {
            if (!connected) return
            val raw = decryptValid(frame) ?: continue
            if (!admit(Reply(TransportNotification(first.sequence, connectionId, raw), first.atMs))) { poison(); return }
            if (closed) return
            onReply(raw.copyOf(), first.atMs)
        }
    }

    private fun decryptValid(frame: ByteArray): ByteArray? {
        val raw = decrypt(frame) ?: return null
        return raw.takeIf { it.size == (frame[2].toInt() and 0xFF) + 5 && XiaomiReply.parse(it) != null }
    }

    private fun admit(reply: Reply): Boolean = synchronized(monitor) {
        val size = reply.notification.bytes.size
        when {
            closed -> true
            replies.size >= maxReplies || replyBytes + size > maxReplyBytes -> false
            else -> {
                replies.addLast(reply)
                replyBytes += size
                monitor.notifyAll()
                true
            }
        }
    }

    private fun extract(output: MutableList<Pair<Ingress, ByteArray>>) {
        while (ingress.size >= 3) {
            val iterator = ingress.iterator()
            val first = iterator.next()
            val second = iterator.next()
            val size = iterator.next().value.toInt() and 0xFF
            if (first.value != 0x55.toByte() || second.value != 0xAB.toByte() || size < 3) {
                ingress.removeFirst()
                continue
            }
            val length = size + 16
            if (ingress.size < length) return
            val frame = ingress.take(length).map { it.value }.toByteArray()
            val sum = frame.sliceArray(2 until length - 2).sumOf { it.toInt() and 0xFF }
            val checksum = sum.inv() and 0xFFFF
            val stored = (frame[length - 2].toInt() and 0xFF) or ((frame.last().toInt() and 0xFF) shl 8)
            if (checksum != stored) { ingress.removeFirst(); continue }
            repeat(length) { ingress.removeFirst() }
            output.add(first to frame)
        }
    }

    override fun awaitNotify(timeoutMs: Long): ByteArray? = awaitNotifyAfter(timeoutMs, -1)?.bytes

    override fun awaitNotifyAfter(timeoutMs: Long, afterSequence: Long): TransportNotification? {
        require(timeoutMs in 1..30_000) { "reply wait outside bounded policy" }
        val start = System.nanoTime()
        synchronized(monitor) {
            while (!closed) {
                while (replies.isNotEmpty()) {
                    val reply = replies.removeFirst()
                    replyBytes -= reply.notification.bytes.size
                    if (reply.notification.sequence > afterSequence) {
                        lastReplyAtMs = reply.atMs
                        return reply.notification
                    }
                }
                val remaining = timeoutMs - (System.nanoTime() - start) / 1_000_000
                if (remaining <= 0) return null
                monitor.wait(remaining)
            }
        }
        return null
    }

    fun close() = synchronized(monitor) {
        closed = true
        ingress.clear()
        replies.clear()
        replyBytes = 0
        monitor.notifyAll()
    }

    fun poison() {
        val notify = synchronized(monitor) {
            if (closed) false else { close(); true }
        }
        if (notify) onPoison()
    }
}
