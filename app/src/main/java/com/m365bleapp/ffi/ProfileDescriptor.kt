package com.m365bleapp.ffi

/** 車款資訊由 Rust 提供；Android 不推測車款能力或驗證狀態。 */
data class ProfileDescriptor(
    val modelId: Int,
    val verified: Boolean,
    val cryptoStrategy: Int,
    val capabilities: Int,
) {
    val supportsLock: Boolean get() = capabilities and 1 != 0
    val supportsLight: Boolean get() = capabilities and 2 != 0
    val supportsRideMode: Boolean get() = capabilities and 4 != 0

    companion object {
        fun decode(bytes: ByteArray): List<ProfileDescriptor> {
            require(bytes.size >= 2 && bytes[0].toInt() == 1) { "Unsupported profile descriptor version" }
            val count = bytes[1].toInt() and 0xff
            require(bytes.size == 2 + count * 4) { "Truncated profile descriptors" }
            return List(count) { index ->
                val offset = 2 + index * 4
                val verified = bytes[offset + 1].toInt() and 0xff
                val crypto = bytes[offset + 2].toInt() and 0xff
                val capabilities = bytes[offset + 3].toInt() and 0xff
                require(verified in 0..1 && crypto in 0..2 && capabilities and 7 == capabilities)
                ProfileDescriptor(bytes[offset].toInt() and 0xff, verified == 1, crypto, capabilities)
            }.also { profiles -> require(profiles.map { it.modelId }.distinct().size == profiles.size) }
        }
    }
}
