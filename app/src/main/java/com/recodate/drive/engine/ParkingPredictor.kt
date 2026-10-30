package com.recodate.drive.engine

import com.recodate.drive.model.OccupancySource
import com.recodate.drive.model.ParkingLot
import com.recodate.drive.model.ParkingStatus
import com.recodate.drive.model.Place
import kotlin.math.max
import kotlin.math.min

/**
 * 실시간 정보가 없을 때 주차장 혼잡도를 예상한다.
 *
 * 근거로 쓰는 것: 시간대·요일, 반경 300m 안 장소 수(수요), 주차면 수(공급).
 * 결과는 항상 "예상"으로 표시한다. 근거가 하나도 없으면 "정보 없음".
 */
object ParkingPredictor {

    fun predict(
        lot: ParkingLot,
        nearbyPlaces: List<Place>,
        minuteOfDay: Int,
        isWeekend: Boolean,
    ): ParkingStatus {
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
        val demand = nearbyPlaces.count { Geo.distanceM(it.point, lot.point) <= 300 }
        val demandLoad = min(1.0, demand / 12.0) // 장소 12곳 이상이면 최대 수요

        val capacity = lot.capacity
        val supplyRelief = when {
            capacity == null -> 0.0
            capacity >= 300 -> 0.2
            capacity >= 100 -> 0.1
            capacity < 30 -> -0.1
            else -> 0.0
        }
        // 무료 공영 주차장은 더 빨리 찬다
        val freePenalty = if (lot.feePer30Min == 0) 0.1 else 0.0

        val occupancy = (0.6 * timeLoad + 0.4 * demandLoad - supplyRelief + freePenalty)
        return ParkingStatus(
            occupancy = max(0.05, min(0.98, occupancy)),
            source = OccupancySource.PREDICTED,
            availableSpaces = null,
        )
    }
}
