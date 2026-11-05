package com.recodate.drive.vehicle

import android.content.Context
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import com.recodate.drive.model.VehicleEnergy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** DRIVING = 전진 기어(D·수동 단수), MANEUVERING = 후진·중립(주차 조정 등). P가 아니면 모두 주행 중 제한 */
enum class DriveState { PARKED, DRIVING, MANEUVERING, UNKNOWN }

data class VehicleState(
    val drive: DriveState = DriveState.UNKNOWN,
    val energy: VehicleEnergy = VehicleEnergy.UNKNOWN,
    /** Pleos Vehicle SDK에서 실제 값을 받고 있는지 */
    val connected: Boolean = false,
) {
    /** 주행 중 제한: P단이 확인된 경우에만 풀어 준다 (모르면 안전하게 제한) */
    val restricted: Boolean get() = drive != DriveState.PARKED
}

interface VehicleGateway {
    val state: StateFlow<VehicleState>
    fun start()
    fun stop()
}

/** Automotive hardware must never inherit the demo's parked state. */
internal fun effectiveVehicleState(isAutomotive: Boolean, real: VehicleState, demo: VehicleState): VehicleState =
    if (isAutomotive) {
        if (real.connected) real else VehicleState(energy = real.energy)
    } else demo.copy(connected = false)

/**
 * Pleos Vehicle SDK 구현.
 * - 기어: Gear.registerGearSelection (P = 정차, 그 밖 = 주행)
 * - 에너지: EvBattery.getEvBatteryStateOfCharge, Fuel.getFuelPercent
 * 권한: pleos.car.permission.CAR_DRIVE, CAR_ENERGY
 */
class PleosVehicleGateway(context: Context) : VehicleGateway {
    private val vehicle = Vehicle(context.applicationContext)
    private val _state = MutableStateFlow(VehicleState())
    override val state: StateFlow<VehicleState> = _state

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) = onGear(gear)
        override fun onFailed(e: Exception) { gearFailed(e) }
    }
    private val socListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(value: Int?) {
            _state.update { it.copy(energy = it.energy.copy(evSocPercent = value)) }
        }
        override fun onFailed(e: Exception) { /* 전기차가 아니면 실패한다 */ }
    }
    private val fuelListener = object : FuelPercentListener {
        override fun onFuelPercentUpdated(value: Int?) {
            _state.update { it.copy(energy = it.energy.copy(fuelPercent = value)) }
        }
        override fun onFailed(e: Exception) { }
    }

    override fun start() {
        vehicle.initialize()
        val gear = vehicle.getGear()
        gear.getCurrentGearSelectionState(
            { g -> onGear(g) },
            { e -> gearFailed(e) },
        )
        gear.registerGearSelection(gearListener)

        val ev = vehicle.getEvBattery()
        ev.getEvBatteryStateOfCharge(
            { v -> _state.update { it.copy(energy = it.energy.copy(evSocPercent = v)) } },
            { },
        )
        ev.registerEvBatteryStateOfCharge(socListener)

        val fuel = vehicle.getFuel()
        fuel.getFuelPercent(
            { v -> _state.update { it.copy(energy = it.energy.copy(fuelPercent = v)) } },
            { },
        )
        fuel.registerFuelPercent(fuelListener)
    }

    private fun onGear(g: VehicleGear?) {
        val drive = when (g) {
            VehicleGear.GEAR_PARK -> DriveState.PARKED
            null, VehicleGear.GEAR_UNKNOWN -> DriveState.UNKNOWN
            VehicleGear.GEAR_REVERSE, VehicleGear.GEAR_NEUTRAL -> DriveState.MANEUVERING
            else -> DriveState.DRIVING
        }
        _state.update { it.copy(drive = drive, connected = true) }
    }

    private fun gearFailed(e: Exception) {
        _state.update { it.copy(drive = DriveState.UNKNOWN, connected = false) }
        Log.w(TAG, "gear unavailable", e)
    }

    override fun stop() {
        runCatching {
            vehicle.getGear().unregisterGearSelection(gearListener)
            vehicle.getEvBattery().unregisterEvBatteryStateOfCharge(socListener)
            vehicle.getFuel().unregisterFuelPercent(fuelListener)
        }
        runCatching { vehicle.release() }
    }

    companion object { private const val TAG = "PleosVehicle" }
}

/** Pleos 차량이 아닌 기기(휴대폰 에뮬레이터 등)에서 쓰는 데모. 시연 패널에서 값을 바꾼다 */
class DemoVehicleGateway : VehicleGateway {
    private val _state = MutableStateFlow(
        VehicleState(drive = DriveState.PARKED, energy = VehicleEnergy(evSocPercent = 64, fuelPercent = null)),
    )
    override val state: StateFlow<VehicleState> = _state
    override fun start() {}
    override fun stop() {}

    fun setDriving(driving: Boolean) =
        _state.update { it.copy(drive = if (driving) DriveState.DRIVING else DriveState.PARKED) }

    fun setSoc(percent: Int) = _state.update { it.copy(energy = VehicleEnergy(percent, null)) }
}
