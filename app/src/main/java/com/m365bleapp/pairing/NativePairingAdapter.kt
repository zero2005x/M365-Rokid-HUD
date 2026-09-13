package com.m365bleapp.pairing

import com.m365bleapp.ffi.M365Native

class NativePairingAdapter(private val native: M365Native) : PairingNative {
    override fun begin(name: String, key: ByteArray): Long = native.beginPairingSafe(name, key)
    override fun next(handle: Long): ByteArray = native.pairingNextSafe(handle)
    override fun receive(handle: Long, bytes: ByteArray): Int = native.pairingReceiveSafe(handle, bytes)
    override fun serial(handle: Long, serial: String): Boolean = native.pairingSetSerialSafe(handle, serial)
    override fun free(handle: Long) = native.freePairingSafe(handle)
}
