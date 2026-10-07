package com.m365bleapp.bond

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.time.Instant
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

enum class BondFamily(val id: String, val bytes: Int, val suffix: String) {
    XIAOMI("xiaomi_mi", 12, "_token"), NINEBOT("ninebot_crypto", 16, "_nb_random")
}

class BondEntry(val mac: String, val family: BondFamily, val credential: ByteArray,
                val label: String? = null, val model: String? = null) : AutoCloseable {
    init {
        require(mac.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")))
        require(credential.size == family.bytes)
        validateText(label); validateText(model)
    }
    fun duplicate() = BondEntry(mac, family, credential.copyOf(), label, model)
    override fun close() { credential.fill(0) }
    val maskedMac get() = "••:••:••:••:" + mac.takeLast(5)
    companion object {
        fun validateText(value: String?) {
            require(value == null || (value.codePointCount(0, value.length) <= 64 && value.none { Character.isISOControl(it) }))
        }
    }
}

class BondDocument(val entries: List<BondEntry>, val skipped: Int = 0) : AutoCloseable {
    override fun close() { entries.forEach { it.close() } }
}

object BondInput {
    fun mac(value: String): String {
        val raw = value.trim().uppercase(Locale.ROOT)
        require(raw.matches(Regex("[0-9A-F]{12}|([0-9A-F]{2}:){5}[0-9A-F]{2}|([0-9A-F]{2}-){5}[0-9A-F]{2}")))
        return raw.replace(":", "").replace("-", "").chunked(2).joinToString(":")
    }
    fun hex(chars: CharArray, size: Int, manual: Boolean = false): ByteArray {
        val clean = CharArray(chars.size)
        var count = 0
        try {
            var start = 0
            if (manual) {
                while (start < chars.size && chars[start].isWhitespace()) start++
                if (start + 1 < chars.size && chars[start] == '0' && chars[start + 1].lowercaseChar() == 'x') start += 2
            }
            for (i in start until chars.size) {
                val c = chars[i]
                if (manual && (c.isWhitespace() || c == ':')) continue
                require(c in '0'..'9' || c.lowercaseChar() in 'a'..'f')
                clean[count++] = c
            }
            require(count == size * 2)
            return ByteArray(size) { i -> ((clean[i * 2].digitToInt(16) shl 4) or clean[i * 2 + 1].digitToInt(16)).toByte() }
        } finally { clean.fill('\u0000') }
    }
    fun strong(password: CharArray) = password.size >= 10 && password.toSet().size >= 4
    fun passwords(first: CharArray, second: CharArray) = strong(first) && first.contentEquals(second)
}

/** Independent implementation of the public RFBOND v1 wire specification. */
object BondEnvelope {
    const val MAX_BYTES = 256 * 1024
    const val DEFAULT_ITERATIONS = 600_000
    private val magic = "RFBOND".toByteArray(Charsets.US_ASCII)
    fun checkHeader(file: ByteArray): Int {
        require(file.size in 55..MAX_BYTES)
        require(magic.indices.all { file[it] == magic[it] } && file[6] == 1.toByte())
        return ByteBuffer.wrap(file, 7, 4).int.also { require(it in 100_000..10_000_000) }
    }
    fun seal(entries: List<BondEntry>, password: CharArray, createdAt: String = Instant.now().toString(),
             iterations: Int = DEFAULT_ITERATIONS,
             salt: ByteArray = ByteArray(16).also(SecureRandom()::nextBytes),
             nonce: ByteArray = ByteArray(12).also(SecureRandom()::nextBytes)): ByteArray {
        require(BondInput.strong(password)); require(iterations in 100_000..10_000_000)
        require(salt.size == 16 && nonce.size == 12)
        Instant.parse(createdAt)
        val header = ByteBuffer.allocate(39).put(magic).put(1.toByte()).putInt(iterations).put(salt).put(nonce).array()
        val plain = BondJson.encode(entries, createdAt)
        return try {
            val encrypted = crypt(Cipher.ENCRYPT_MODE, plain, password, iterations, header)
            try { header + encrypted } finally { encrypted.fill(0) }
        }
        finally { plain.fill(0) }
    }
    fun open(file: ByteArray, password: CharArray): BondDocument {
        val iterations = checkHeader(file) // Bound size/work before PBKDF2.
        val header = file.copyOfRange(0, 39)
        val encrypted = file.copyOfRange(39, file.size)
        val plain = try { crypt(Cipher.DECRYPT_MODE, encrypted, password, iterations, header) }
        finally { encrypted.fill(0) }
        return try { BondJson.decode(plain) } finally { plain.fill(0) }
    }
    private fun crypt(mode: Int, input: ByteArray, password: CharArray, iterations: Int, header: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, header.copyOfRange(11, 27), iterations, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, header.copyOfRange(27, 39)))
            cipher.updateAAD(header)
            return cipher.doFinal(input)
        } finally { key.fill(0) }
    }
}

/** Streaming bytes on export; credentials never become immutable hex Strings. */
object BondJson {
    private class WipeOutput : ByteArrayOutputStream() {
        fun wipe() { buf.fill(0); reset() }
    }
    fun encode(entries: List<BondEntry>, createdAt: String): ByteArray {
        require(entries.size <= 64 && entries.map { it.mac }.distinct().size == entries.size)
        val out = WipeOutput()
        fun text(s: String) { out.write(s.toByteArray(Charsets.UTF_8)) }
        fun quoted(s: String) {
            text(buildString {
                append('"')
                s.forEach { c -> when(c) {
                    '"' -> append("\\\""); '\\' -> append("\\\\")
                    else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
                } }
                append('"')
            })
        }
        try {
            text("{\"schema\":\"rideflux-bond/v1\",\"createdAt\":"); quoted(createdAt); text(",\"entries\":[")
            entries.forEachIndexed { i, e ->
                require(e.credential.size == e.family.bytes); BondEntry.validateText(e.label); BondEntry.validateText(e.model)
                if (i > 0) text(",")
                text("{\"mac\":"); quoted(e.mac); text(",\"family\":"); quoted(e.family.id); text(",\"credentialHex\":\"")
                e.credential.forEach { b ->
                    out.write("0123456789abcdef"[(b.toInt() and 255) ushr 4].code)
                    out.write("0123456789abcdef"[b.toInt() and 15].code)
                }
                text("\"")
                e.label?.let { text(",\"label\":"); quoted(it) }
                e.model?.let { text(",\"model\":"); quoted(it) }
                text("}")
            }
            text("]}"); return out.toByteArray()
        } finally { out.wipe() }
    }
    fun decode(bytes: ByteArray, store: Boolean = false): BondDocument {
        require(bytes.size <= BondEnvelope.MAX_BYTES)
        val chars = CharArray(bytes.size)
        val buffer = CharBuffer.wrap(chars)
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val result = decoder.decode(ByteBuffer.wrap(bytes), buffer, true)
        if (result.isError) { chars.fill('\u0000'); result.throwException() }
        decoder.flush(buffer)
        val parser = Parser(chars, buffer.position())
        val entries = mutableListOf<BondEntry>()
        try {
            var schema: String? = null; var created: String? = null; var hasEntries = false; var skipped = 0
            val addresses = mutableSetOf<String>()
            parser.obj { name -> when(name) {
                "schema" -> schema = parser.string()
                "createdAt" -> created = parser.string()
                "entries" -> {
                    require(!hasEntries); hasEntries = true
                    var count = 0
                    parser.array {
                        require(++count <= 64)
                        var mac: String? = null; var family: String? = null; var hex: CharArray? = null
                        var label: String? = null; var model: String? = null
                        try {
                            parser.obj { key -> when(key) {
                                "mac" -> mac = parser.string(); "family" -> family = parser.string()
                                "credentialHex" -> { hex?.fill('\u0000'); hex = parser.stringChars() }
                                "label" -> label = parser.string(); "model" -> model = parser.string()
                                else -> parser.skip()
                            } }
                            val address = requireNotNull(mac)
                            require(address.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")) && addresses.add(address))
                            BondEntry.validateText(label); BondEntry.validateText(model)
                            val kind = BondFamily.entries.firstOrNull { it.id == requireNotNull(family) }
                            val credential = requireNotNull(hex)
                            require(credential.isNotEmpty() && credential.size % 2 == 0 && credential.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' })
                            if (kind == null) skipped++
                            else entries.add(BondEntry(address, kind, BondInput.hex(credential, kind.bytes), label, model))
                        } finally { hex?.fill('\u0000') }
                    }
                }
                else -> parser.skip()
            } }
            parser.end(); require(schema == "rideflux-bond/v1" && hasEntries)
            if (!(store && created == "store")) Instant.parse(requireNotNull(created))
            return BondDocument(entries, skipped)
        } catch (e: Exception) { entries.forEach { it.close() }; throw e }
        finally { chars.fill('\u0000') }
    }
    private class Parser(private val chars: CharArray, private val length: Int) {
        private var p = 0
        private fun ws() { while (p < length && chars[p] in " \r\n\t") p++ }
        private fun take(c: Char): Boolean { ws(); return if (p < length && chars[p] == c) { p++; true } else false }
        private fun expect(c: Char) { require(take(c)) }
        fun end() { ws(); require(p == length) }
        fun obj(field: (String) -> Unit) {
            expect('{'); if (take('}')) return
            val names = mutableSetOf<String>()
            do { val name = string(); require(names.add(name)); expect(':'); field(name) } while(take(','))
            expect('}')
        }
        fun array(item: () -> Unit) { expect('['); if (take(']')) return; do { item() } while(take(',')); expect(']') }
        fun string(): String { val a = stringChars(); return try { String(a) } finally { a.fill('\u0000') } }
        fun stringChars(): CharArray {
            expect('"'); val temp = CharArray(length - p); var n = 0
            try {
                while (p < length) {
                    var c = chars[p++]
                    if (c == '"') return temp.copyOf(n)
                    require(c.code >= 32)
                    if (c == '\\') {
                        require(p < length)
                        c = when(val escaped = chars[p++]) {
                            '"', '\\', '/' -> escaped; 'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                            'u' -> { require(p + 4 <= length); var code = 0; repeat(4) { code = code * 16 + chars[p++].digitToInt(16) }; code.toChar() }
                            else -> error("Invalid JSON escape")
                        }
                    }
                    temp[n++] = c
                }
                error("Unterminated JSON string")
            } finally { temp.fill('\u0000') }
        }
        fun skip(depth: Int = 0) {
            require(depth < 32); ws(); require(p < length)
            when(chars[p]) {
                '{' -> obj { skip(depth + 1) }; '[' -> array { skip(depth + 1) }; '"' -> stringChars().fill('\u0000')
                else -> {
                    val start = p
                    while (p < length && chars[p] !in ",]} \r\n\t") p++
                    val value = String(chars, start, p - start)
                    require(value in listOf("null", "true", "false") || value.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")))
                }
            }
        }
    }
}
