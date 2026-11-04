package com.recodate.drive.vehicle

import org.junit.Assert.*
import org.junit.Test

class VehicleSafetyTest {
    private val demo = VehicleState(drive = DriveState.PARKED)

    @Test fun automotiveWithoutSignalStaysRestricted() {
        assertTrue(effectiveVehicleState(true, VehicleState(), demo).restricted)
    }

    @Test fun lostConnectionDoesNotReuseLastParkedGear() {
        val old = VehicleState(drive = DriveState.PARKED, connected = false)
        assertEquals(DriveState.UNKNOWN, effectiveVehicleState(true, old, demo).drive)
    }

    @Test fun onlyConfirmedParkUnlocksAutomotive() {
        DriveState.entries.forEach { gear ->
            val state = effectiveVehicleState(true, VehicleState(gear, connected = true), demo)
            assertEquals(gear != DriveState.PARKED, state.restricted)
        }
    }

    @Test fun nonAutomotiveRetainsDemoControls() {
        assertFalse(effectiveVehicleState(false, VehicleState(), demo).restricted)
        assertTrue(effectiveVehicleState(false, VehicleState(), demo.copy(drive = DriveState.DRIVING)).restricted)
    }
}
