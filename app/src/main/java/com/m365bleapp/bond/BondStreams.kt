package com.m365bleapp.bond

import java.io.InputStream

class UnsupportedBondBackup : IllegalArgumentException()

object BondStreams {
    /** Reads at most limit+1 bytes, even when a provider reports a false file size. */
    fun read(input: InputStream): ByteArray {
        val bytes = ByteArray(BondEnvelope.MAX_BYTES + 1)
        try {
            var count = 0
            while (count < bytes.size) {
                val n = input.read(bytes, count, bytes.size - count)
                if (n < 0) break
                if (n == 0) {
                    val next = input.read()
                    if (next < 0) break
                    bytes[count++] = next.toByte()
                } else count += n
            }
            if (count > BondEnvelope.MAX_BYTES) throw UnsupportedBondBackup()
            val magic = "RFBOND".toByteArray(Charsets.US_ASCII)
            if (count < magic.size || magic.indices.any { bytes[it] != magic[it] }) throw UnsupportedBondBackup()
            val file = bytes.copyOf(count)
            try { BondEnvelope.checkHeader(file); return file }
            catch (e: Exception) { file.fill(0); throw e }
        } finally { bytes.fill(0) }
    }
}
