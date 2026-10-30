package com.recodate.drive.engine

import com.recodate.drive.model.GeoPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** 실제 길은 직선보다 길다. 도보·차량 우회 계수 */
    const val WALK_DETOUR = 1.3
    const val DRIVE_DETOUR = 1.4

    /** 직선거리(m), 하버사인 공식 */
    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }

    /** 예상 도보 거리(m) */
    fun walkM(a: GeoPoint, b: GeoPoint): Int = (distanceM(a, b) * WALK_DETOUR).roundToInt()

    /** 예상 도보 시간(분), 시속 4km */
    fun walkMin(meters: Int): Int = (meters / 66.7).roundToInt()

    /**
     * 실시간 교통 정보가 없을 때 쓰는 예상 운전 시간(분).
     * 출퇴근·주말 낮 시간대는 평균 속도를 낮춘다. 주차·하차 3분 포함.
     */
    fun estimateDriveMin(a: GeoPoint, b: GeoPoint, minuteOfDay: Int, isWeekend: Boolean): Int {
        val km = distanceM(a, b) * DRIVE_DETOUR / 1000.0
        val hour = minuteOfDay / 60
        val citySpeed = when {
            !isWeekend && (hour in 7..9 || hour in 17..19) -> 18.0
            isWeekend && hour in 11..18 -> 22.0
            else -> 32.0
        }
        // 처음 15km는 시내 속도, 그 뒤는 고속도로·국도 평균 75km/h
        val cityKm = minOf(km, 15.0)
        val highwayKm = (km - 15.0).coerceAtLeast(0.0)
        return ((cityKm / citySpeed + highwayKm / 75.0) * 60).roundToInt() + 3
    }
}
