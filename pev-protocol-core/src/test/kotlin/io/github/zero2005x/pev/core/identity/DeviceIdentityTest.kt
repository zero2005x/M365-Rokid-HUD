package io.github.zero2005x.pev.core.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityTest {
    @Test
    fun defaultIsUnknownAndNotExact() {
        assertFalse(DeviceIdentity().isExact)
        assertEquals(DeviceIdentity().profileKey, DeviceIdentity().profileKey)
    }

    @Test
    fun gattHintNeverMakesModelExact() {
        assertFalse(DeviceIdentity(Family.BEGODE, "A2").isExact)
    }

    @Test
    fun userSelectionOrInBandIsExact() {
        assertTrue(DeviceIdentity(Family.BEGODE, "A2", source = IdentitySource.USER_SELECTED).isExact)
        assertTrue(DeviceIdentity(Family.XIAOMI_SCOOTER, "M365", source = IdentitySource.IN_BAND_QUERY).isExact)
        assertFalse(DeviceIdentity(Family.BEGODE, null, source = IdentitySource.USER_SELECTED).isExact)
    }

    @Test
    fun profileKeyChangesWithFirmware() {
        val a = DeviceIdentity(Family.BEGODE, "A2", "1")
        assertFalse(a.profileKey == a.copy(firmware = "2").profileKey)
    }
    @Test
    fun profileKeyIncludesPackAndSourceAndIsIndependentOfMapOrder() {
        val a = DeviceIdentity(Family.BEGODE, "A2", packParams = linkedMapOf("cells" to "20", "capacity" to "100"))
        assertEquals(a.profileKey, a.copy(packParams = linkedMapOf("capacity" to "100", "cells" to "20")).profileKey)
        assertFalse(a.profileKey == a.copy(packParams = mapOf("cells" to "16")).profileKey)
        assertFalse(a.profileKey == a.copy(source = IdentitySource.USER_SELECTED).profileKey)
        assertFalse(a.copy(model = "a|b", firmware = "c").profileKey == a.copy(model = "a", firmware = "b|c").profileKey)
    }

    @Test fun blankModelIsNotAnExactIdentity() {
        assertFalse(DeviceIdentity(Family.BEGODE, "  ", source = IdentitySource.USER_SELECTED).isExact)
    }

}
