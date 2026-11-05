package com.recodate.drive.data

import com.recodate.drive.engine.CourseRecommender
import com.recodate.drive.engine.Geo
import com.recodate.drive.engine.ParkingPredictor
import com.recodate.drive.engine.withLiveDriveToStart
import com.recodate.drive.engine.withSunsetNotes
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
    /** 디버그 빌드에서만: 추천 입력 데이터를 저장해 API 호출 없이 다시 테스트할 수 있게 한다 */
    private val snapshotSink: ((Snapshot) -> Unit)? = null,
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
        /** false면 TMAP 경로(실시간 교통)를 부르지 않는다. 테스트 녹화용 */
        useLiveTraffic: Boolean = true,
        snapshotLabel: String = anchor.name,
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

        snapshotSink?.let { sink ->
            cancellableAttempt { sink(Snapshot(snapshotLabel, anchor, mode, minute, weekend, w, origin, placeList, lots)) }
        }

        val realtimeStatus = cancellableAttempt { realtime.status(lots) }.getOrDefault(emptyMap())
        val predicted = ParkingPredictor.predictAll(lots, placeList, arriveMinute, weekend)
        cancellableAttempt {
            android.util.Log.d("CourseService", "parking lots=${lots.size} places=${placeList.size} " +
                "arrive=${arriveMinute / 60}h weekend=$weekend levels=" +
                predicted.values.groupingBy { it.level }.eachCount())
        }
        val recommender = CourseRecommender { lot ->
            realtimeStatus[lot.id] ?: predicted.getValue(lot.id)
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
        if (origin != null && useLiveTraffic) {
            courses = courses.map { c ->
                async {
                    traffic.driveMinutes(origin, c.firstNaviTarget.point)?.let { c.withLiveDriveToStart(it) } ?: c
                }
            }.awaitAll()
        }

        // 실시간 교통으로 시각이 옮겨진 최종 일정 기준으로 일몰을 확인한다 (인터넷 없이 계산)
        val sunset = com.recodate.drive.engine.SunTimes.sunsetMinuteKst(anchor.point, t.toLocalDate())
        courses = courses.map { it.withSunsetNotes(minute, sunset) }

        Recommendation(
            request = request,
            courses = courses,
            weather = w,
            usedDemoData = placeList.any { it.source == DataSource.DEMO } || lots.any { it.source == DataSource.DEMO },
        )
    }

    /**
     * 내비 목적지처럼 이름만 있는 기준점이면, 같은 장소를 후보에서 찾아 코스에 꼭 넣는다.
     * - 같은 장소로 보는 조건: 150m 이내 + 괄호·공백을 뺀 이름이 똑같을 때
     *   ("스타필드 하남" ≠ "피자스탠드 스타필드하남", "불국사" ≠ "불국사박물관 일주문매표소")
     * - 못 찾으면 "관광" 장소로 만들어 넣는다.
     * - "내 주변"이나 주차장(내비 목적지가 주차장인 경우)은 중심점으로만 쓴다.
     */
    internal fun withAnchorPlace(anchor: Anchor, candidates: List<Place>): Anchor {
        if (anchor.place != null || anchor.name.isBlank() || anchor.name == MY_AREA) return anchor
        if (isParkingName(anchor.name)) return anchor
        val key = normalizeName(anchor.name)
        val match = candidates
            .filter { Geo.distanceM(it.point, anchor.point) <= 150 }
            .filter { val n = normalizeName(it.name); n.isNotEmpty() && n == key }
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

/** 이름 비교용: [..], (..) 안의 부가 설명과 공백·특수문자를 빼고 소문자로 ("강남역[수도권2호선]" → "강남역") */
internal fun normalizeName(s: String) =
    s.replace(Regex("\\[[^]]*]|\\([^)]*\\)"), "").lowercase().filter { it.isLetterOrDigit() }

/** 주차장 이름인지 (내비 목적지가 주차장이면 "꼭 들를 곳"이 아니다) */
internal fun isParkingName(name: String) = "주차장" in name || name.trim().endsWith("주차")

