package com.m365bleapp.protocol

import org.junit.Assert.*
import org.junit.Test

class VehicleProfileStoreTest {
    private val models = ScooterModelRegistry.selectableModels.filter { it.producesTelemetry }
    @Test fun `pending legacy value needs explicit target and is consumed once`() {
        val store = VehicleProfileStore()
        assertNull(store.manualModel("A"))
        var pending: ScooterModel? = models.first()
        store.resolvePending("A", pending) { pending = null }
        assertEquals(models.first(), store.manualModel("a"))
        assertNull(pending)
        assertNull(store.manualModel("B"))
    }
    @Test fun `discard does not bind a vehicle`() {
        val store = VehicleProfileStore()
        var cleared = false
        store.resolvePending(null, models.first()) { cleared = true }
        assertTrue(cleared)
        assertTrue(store.records.value.isEmpty())
    }
    @Test fun `profiles survive reload and isolate vehicles`() {
        var persisted = ""
        val store = VehicleProfileStore(persist = { persisted = it })
        store.bind("A", models[0]); store.bind("B", models[1])
        val restored = VehicleProfileStore(persisted)
        assertEquals(models[0], restored.manualModel("a"))
        assertEquals(models[1], restored.manualModel("B"))
        store.bind("A", null)
        assertNull(store.manualModel("A"))
        assertEquals(models[1], store.manualModel("B"))
    }
    @Test fun `editing active vehicle updates flow and logger synchronously`() {
        val store = VehicleProfileStore()
        var logged: Identification? = null
        val identity = VehicleIdentificationState(store) { logged = it }
        identity.activate("A", "MIScooter")
        identity.bind("a", models[1])
        assertEquals(models[1], identity.state.value.model)
        assertEquals(identity.state.value, logged)
        identity.bind("B", models[0])
        assertEquals(models[1], identity.state.value.model)
        identity.activate("B", null)
        assertEquals(models[0], identity.state.value.model)
    }
}
