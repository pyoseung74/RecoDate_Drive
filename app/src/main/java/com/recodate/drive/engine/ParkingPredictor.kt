package com.recodate.drive.engine

import com.recodate.drive.model.OccupancySource
import com.recodate.drive.model.ParkingLot
import com.recodate.drive.model.ParkingStatus
import com.recodate.drive.model.Place
import kotlin.math.max
import kotlin.math.min

/**
 * 실시간 정보가 없을 때 주차장 혼잡도를 예상한다. 결과는 항상 "예상"으로 표시한다.
 *
 * 근거:
 * - 시간대·요일 (방문 예정 시각 기준)
 * - 수요: 각 장소를 걸어서 400m 안의 "가장 가까운 주차장" 한 곳에만 배정하고, 배정된 장소 수를 센다.
 *   상권 한가운데서는 반경 안 장소 수가 모든 주차장에서 최대가 되기 때문에, 이렇게 나눠야 주차장끼리 차이가 난다.
 * - 공급: 주차면 수 (모르면 반영 안 함)
 * - 무료 주차장은 더 빨리 찬다
 */
object ParkingPredictor {

    private const val ASSIGN_RADIUS_M = 400.0
    /** 가장 가까운 주차장으로 배정된 장소가 이만큼이면 수요 최대 */
    private const val FULL_DEMAND_PLACES = 8.0

    /** 주차장 여러 곳을 한꺼번에 예상한다 (수요를 주차장끼리 나눠 가짐) */
    fun predictAll(
        lots: List<ParkingLot>,
        places: List<Place>,
        minuteOfDay: Int,
        isWeekend: Boolean,
    ): Map<String, ParkingStatus> {
        val assigned = mutableMapOf<String, Int>()
        for (p in places) {
            val nearest = lots
                .map { it to Geo.distanceM(it.point, p.point) }
                .filter { it.second <= ASSIGN_RADIUS_M }
                .minByOrNull { it.second }?.first ?: continue
            assigned.merge(nearest.id, 1, Int::plus)
        }
        return lots.associate { lot ->
            lot.id to status(lot, assigned[lot.id] ?: 0, minuteOfDay, isWeekend)
        }
    }

    /** 주차장 한 곳만 예상할 때 (주변 장소를 모두 이 주차장 수요로 본다) */
    fun predict(lot: ParkingLot, nearbyPlaces: List<Place>, minuteOfDay: Int, isWeekend: Boolean): ParkingStatus =
        predictAll(listOf(lot), nearbyPlaces, minuteOfDay, isWeekend).getValue(lot.id)

    private fun status(lot: ParkingLot, demandPlaces: Int, minuteOfDay: Int, isWeekend: Boolean): ParkingStatus {
        val hour = minuteOfDay / 60
        val timeLoad = when {
            isWeekend && hour in 11..14 -> 0.75
            isWeekend && hour in 15..19 -> 0.8
            isWeekend && hour in 9..10 -> 0.5
            !isWeekend && hour in 11..13 -> 0.65
            !isWeekend && hour in 18..20 -> 0.7
            hour in 21..23 || hour < 7 -> 0.25
            else -> 0.45
        }
        val demandLoad = min(1.0, demandPlaces / FULL_DEMAND_PLACES)

        val capacity = lot.capacity
        val supplyRelief = when {
            capacity == null -> 0.0
            capacity >= 300 -> 0.2
            capacity >= 100 -> 0.1
            capacity < 30 -> -0.1
            else -> 0.0
        }
        val freePenalty = if (lot.feePer30Min == 0) 0.1 else 0.0

        val occupancy = 0.6 * timeLoad + 0.4 * demandLoad - supplyRelief + freePenalty
        return ParkingStatus(
            occupancy = max(0.05, min(0.98, occupancy)),
            source = OccupancySource.PREDICTED,
            availableSpaces = null,
        )
    }
}
