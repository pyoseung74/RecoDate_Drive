package com.recodate.drive.data

import com.recodate.drive.engine.CourseRecommender
import com.recodate.drive.engine.Geo
import com.recodate.drive.engine.ParkingPredictor
import com.recodate.drive.engine.withLiveDriveToStart
import com.recodate.drive.model.Anchor
import com.recodate.drive.model.Course
import com.recodate.drive.model.CourseMode
import com.recodate.drive.model.CourseRequest
import com.recodate.drive.model.DataSource
import com.recodate.drive.model.GeoPoint
import com.recodate.drive.model.Interest
import com.recodate.drive.model.Place
import com.recodate.drive.model.PlaceCategory
import com.recodate.drive.model.VehicleEnergy
import com.recodate.drive.model.Weather
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

data class Recommendation(
    val request: CourseRequest,
    val courses: List<Course>,
    val weather: Weather?,
    /** 화면에 "데모 데이터" 표시를 할지 */
    val usedDemoData: Boolean,
)

class CourseService(
    private val places: PlaceSource,
    private val parking: ParkingSource,
    private val realtime: ParkingRealtimeSource,
    private val traffic: TrafficSource,
    private val weather: WeatherSource,
    private val now: () -> LocalDateTime = { LocalDateTime.now(ZoneId.of("Asia/Seoul")) },
) {
    companion object {
        /** API 호출 수를 줄이려고 차로 이동형 검색 반경은 6km로 제한 */
        const val DRIVE_FETCH_RADIUS_M = 6_000
        const val WALK_FETCH_RADIUS_M = 1_500
    }

    suspend fun recommend(
        anchor: Anchor,
        mode: CourseMode,
        interests: Set<Interest>,
        origin: GeoPoint?,
        energy: VehicleEnergy,
        variation: Int = 0,
    ): Recommendation = coroutineScope {
        val t = now()
        val minute = t.hour * 60 + t.minute
        val weekend = t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY

        // 출발 후 도착할 즈음의 날씨를 본다
        val arriveMinute = minute + (origin?.let { Geo.estimateDriveMin(it, anchor.point, minute, weekend) } ?: 0)

        val fetchRadius = if (mode == CourseMode.PARK_AND_WALK) WALK_FETCH_RADIUS_M else DRIVE_FETCH_RADIUS_M
        val categories = buildSet {
            addAll(listOf(PlaceCategory.FOOD, PlaceCategory.CAFE, PlaceCategory.WALK, PlaceCategory.ATTRACTION, PlaceCategory.INDOOR_ACTIVITY))
            if (energy.isLow) add(if (energy.isEv) PlaceCategory.EV_CHARGER else PlaceCategory.GAS_STATION)
        }

        val weatherJob = async { weather.forecast(anchor.point, arriveMinute) }
        val placesJob = async { places.nearby(anchor.point, fetchRadius, categories) }
        val lotsJob = async { parking.nearby(anchor.point, fetchRadius + 300) }
        val w = weatherJob.await()
        val placeList = placesJob.await()
        val lots = lotsJob.await()

        val realtimeStatus = runCatching { realtime.status(lots) }.getOrDefault(emptyMap())
        val recommender = CourseRecommender { lot ->
            realtimeStatus[lot.id] ?: ParkingPredictor.predict(lot, placeList, arriveMinute, weekend)
        }

        val request = CourseRequest(
            anchor = withAnchorPlace(anchor, placeList),
            mode = mode,
            interests = interests,
            startMinuteOfDay = minute,
            isWeekend = weekend,
            weather = w,
            energy = energy,
            origin = origin,
        )
        var courses = recommender.recommend(request, placeList, lots, variation)

        // 실시간 교통: 코스마다 첫 목적지(주차장)까지 TMAP 경로 1건씩, 병렬
        if (origin != null) {
            courses = courses.map { c ->
                async {
                    traffic.driveMinutes(origin, c.firstNaviTarget.point)?.let { c.withLiveDriveToStart(it) } ?: c
                }
            }.awaitAll()
        }

        Recommendation(
            request = request,
            courses = courses,
            weather = w,
            usedDemoData = placeList.any { it.source == DataSource.DEMO } || lots.any { it.source == DataSource.DEMO },
        )
    }

    /**
     * 내비 목적지처럼 이름만 있는 기준점이면, 같은 장소를 후보에서 찾아 코스에 꼭 넣는다.
     * 못 찾으면 "관광" 장소로 만들어 넣는다. "내 주변"(이름 없음)은 중심점으로만 쓴다.
     */
    private fun withAnchorPlace(anchor: Anchor, candidates: List<Place>): Anchor {
        if (anchor.place != null || anchor.name.isBlank() || anchor.name == MY_AREA) return anchor
        val match = candidates
            .filter { Geo.distanceM(it.point, anchor.point) <= 150 }
            .minByOrNull { Geo.distanceM(it.point, anchor.point) }
        val place = match ?: Place(
            id = "anchor",
            name = anchor.name,
            category = PlaceCategory.ATTRACTION,
            point = anchor.point,
            indoor = false,
            source = DataSource.LIVE,
        )
        return anchor.copy(place = place)
    }
}

const val MY_AREA = "지금 내 주변"
