package com.recodate.drive.data

import com.recodate.drive.engine.Geo
import com.recodate.drive.model.GeoPoint
import com.recodate.drive.model.ParkingLot
import com.recodate.drive.model.ParkingStatus
import com.recodate.drive.model.Place
import com.recodate.drive.model.PlaceCategory
import com.recodate.drive.model.Weather

/** 장소 후보 (TMAP 주변 검색 또는 데모) */
interface PlaceSource {
    suspend fun nearby(center: GeoPoint, radiusM: Int, categories: Set<PlaceCategory>): List<Place>
}

/** 주차장 위치·요금 (TMAP 주변 검색 또는 데모) */
interface ParkingSource {
    suspend fun nearby(center: GeoPoint, radiusM: Int): List<ParkingLot>
}

/** 실시간 주차 혼잡도. 지원하지 않는 주차장이면 null */
interface ParkingRealtimeSource {
    suspend fun status(lots: List<ParkingLot>): Map<String, ParkingStatus>
}

/** 실시간 교통 반영 운전 시간(분). 실패하면 null */
interface TrafficSource {
    suspend fun driveMinutes(from: GeoPoint, to: GeoPoint): Int?
}

/** 방문 예상 시각의 날씨. 실패하면 null */
interface WeatherSource {
    suspend fun forecast(at: GeoPoint, minuteOfDay: Int): Weather?
}

// ───────────────────── 데모 구현 ─────────────────────

class DemoPlaceSource : PlaceSource {
    override suspend fun nearby(center: GeoPoint, radiusM: Int, categories: Set<PlaceCategory>) =
        DemoData.places.filter { it.category in categories && Geo.distanceM(center, it.point) <= radiusM }
}

class DemoParkingSource : ParkingSource {
    override suspend fun nearby(center: GeoPoint, radiusM: Int) =
        DemoData.parkingLots.filter { Geo.distanceM(center, it.point) <= radiusM }
}

/** 실시간 주차 정보가 없는 상태. 모든 주차장을 예상값으로 처리하게 한다 */
class NoRealtimeParking : ParkingRealtimeSource {
    override suspend fun status(lots: List<ParkingLot>) = emptyMap<String, ParkingStatus>()
}

class NoTraffic : TrafficSource {
    override suspend fun driveMinutes(from: GeoPoint, to: GeoPoint): Int? = null
}

class NoWeather : WeatherSource {
    override suspend fun forecast(at: GeoPoint, minuteOfDay: Int): Weather? = null
}

/** 시연용: 비 오는 날 시나리오를 켤 수 있는 날씨 */
class DemoWeatherSource(private val raining: () -> Boolean) : WeatherSource {
    override suspend fun forecast(at: GeoPoint, minuteOfDay: Int) =
        if (raining()) Weather(precipitating = true, rainProbability = 80, temperatureC = 14.0, source = com.recodate.drive.model.DataSource.DEMO)
        else Weather(precipitating = false, rainProbability = 10, temperatureC = 19.0, source = com.recodate.drive.model.DataSource.DEMO)
}

/** 실시간 → 실패하면 데모로 대체 */
class FallbackPlaceSource(private val live: PlaceSource, private val demo: PlaceSource) : PlaceSource {
    override suspend fun nearby(center: GeoPoint, radiusM: Int, categories: Set<PlaceCategory>) =
        cancellableAttempt { live.nearby(center, radiusM, categories) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: demo.nearby(center, radiusM, categories)
}

class FallbackParkingSource(private val live: ParkingSource, private val demo: ParkingSource) : ParkingSource {
    override suspend fun nearby(center: GeoPoint, radiusM: Int) =
        cancellableAttempt { live.nearby(center, radiusM) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: demo.nearby(center, radiusM)
}
