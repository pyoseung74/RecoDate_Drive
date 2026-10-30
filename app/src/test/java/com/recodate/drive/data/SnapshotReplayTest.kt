package com.recodate.drive.data

import com.recodate.drive.engine.CourseRecommender
import com.recodate.drive.engine.Geo
import com.recodate.drive.model.CourseMode
import com.recodate.drive.model.DataSource
import com.recodate.drive.model.GeoPoint
import com.recodate.drive.model.Interest
import com.recodate.drive.model.ParkingLot
import com.recodate.drive.model.Place
import com.recodate.drive.model.PlaceCategory
import com.recodate.drive.model.VehicleEnergy
import com.recodate.drive.model.Weather
import com.recodate.drive.model.isResidentOnlyParking
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import org.junit.Assert.fail

/**
 * 실제 TMAP 데이터 녹화본(testdata/snapshots, git 제외)으로 추천을 API 호출 없이 반복 검사한다.
 * 녹화본이 없으면 건너뛴다. 녹화: 디버그 빌드에서 adb로 목적지를 넣어 실행 (docs/TESTING.md).
 */
class SnapshotReplayTest {

    private val dir = listOf(File("../testdata/snapshots"), File("testdata/snapshots")).firstOrNull { it.isDirectory }

    private fun snapshots(): List<Snapshot> =
        dir?.listFiles { f -> f.extension == "json" }?.sortedBy { it.name }?.map { Snapshot.fromJson(it.readText()) }.orEmpty()

    private class Fixed(val places: List<Place>, val lots: List<ParkingLot>, val weather: Weather?) {
        val placeSource = object : PlaceSource {
            override suspend fun nearby(center: GeoPoint, radiusM: Int, categories: Set<PlaceCategory>) =
                places.filter { it.category in categories }
        }
        val parkingSource = object : ParkingSource {
            override suspend fun nearby(center: GeoPoint, radiusM: Int) = lots
        }
        val weatherSource = object : WeatherSource {
            override suspend fun forecast(at: GeoPoint, minuteOfDay: Int) = weather
        }
    }

    private val rain = Weather(true, 90, 12.0, DataSource.LIVE)
    private val times = listOf(11 * 60, 14 * 60, 18 * 60, 21 * 60)
    private val interestSets = listOf(emptySet<Interest>()) + Interest.entries.map { setOf(it) }

    @Test
    fun everyRecordedDestination_givesValidCourses() = runBlocking {
        val snaps = snapshots()
        assumeTrue("녹화본 없음 → 건너뜀", snaps.isNotEmpty())

        val problems = mutableListOf<String>()
        val summary = mutableListOf<String>()
        var runs = 0

        for (s in snaps) {
            val label = "${s.label}/${s.mode.label}"
            var empty = 0
            var total = 0
            for (weather in listOf(s.weather, rain)) for (minute in times) for (interests in interestSets)
                for (energy in listOf(VehicleEnergy.UNKNOWN, VehicleEnergy(10, null))) {
                    val fx = Fixed(s.places, s.lots, weather)
                    val day = if (s.isWeekend) LocalDateTime.of(2026, 10, 31, 0, 0) else LocalDateTime.of(2026, 10, 30, 0, 0)
                    val service = CourseService(fx.placeSource, fx.parkingSource, NoRealtimeParking(), NoTraffic(), fx.weatherSource,
                        now = { day.plusMinutes(minute.toLong()) })
                    val rec = service.recommend(s.anchor, s.mode, interests, s.origin, energy)
                    runs++; total++
                    val ctx = "$label w=${if (weather == rain) "rain" else "as-is"} t=${minute / 60}h i=${interests.joinToString { it.label }} e=${energy.percent}"
                    if (rec.courses.isEmpty()) { empty++; continue }

                    val anchorPlace = rec.request.anchor.place
                    for (c in rec.courses) {
                        if (anchorPlace != null && c.stops.none { it.place.id == anchorPlace.id })
                            problems += "목적지 누락 [$ctx] ${c.id}"
                        c.stops.filter { isParkingName(it.place.name) }.forEach { problems += "주차장이 장소로 [$ctx] ${it.place.name}" }
                        val lotsUsed = listOfNotNull(c.mainParking) + c.stops.mapNotNull { it.parking }
                        lotsUsed.filter { isResidentOnlyParking(it.name) }.forEach { problems += "주민 전용 주차장 [$ctx] ${it.name}" }
                        val relax = if (c.relaxedLimits) CourseRecommender.RELAX_FACTOR else 1.0
                        if (s.mode == CourseMode.PARK_AND_WALK && c.totalWalkM > CourseRecommender.PW_MAX_TOTAL_WALK_M * relax)
                            problems += "도보 초과 [$ctx] ${c.totalWalkM}m"
                        // 목적지는 700m까지 허용 (화면에 경고 표시)
                        if (s.mode == CourseMode.DRIVE_AND_HOP) c.stops.filter {
                            val limit = if (it.place.id == anchorPlace?.id) CourseRecommender.DH_ANCHOR_MAX_PARK_WALK_M.toDouble()
                            else CourseRecommender.DH_MAX_PARK_WALK_M * relax
                            it.walkInM > limit
                        }.forEach { problems += "주차장-장소 도보 초과 [$ctx] ${it.place.name} ${it.walkInM}m" }
                        if (c.relaxedLimits && c.warnings.none { "늘렸어요" in it }) problems += "완화 표시 누락 [$ctx]"
                        if (c.stops.map { it.place.id }.toSet().size != c.stops.size) problems += "장소 중복 [$ctx] ${c.id}"
                    }
                    if (rec.courses.map { it.id }.toSet().size != rec.courses.size) problems += "같은 코스 반복 [$ctx]"
                }
            if (empty > 0) problems += "코스 0개 [$label] $empty/$total 조합"
            summary += "%-28s 코스 0개 %3d/%d".format(label, empty, total)
        }
        println("── 재생 테스트: 녹화본 ${snaps.size}개, 실행 $runs 번")
        summary.forEach { println(it) }
        if (problems.isNotEmpty()) {
            val grouped = problems.groupBy { it.substringBefore(" [") }
            val text = grouped.entries.joinToString("\n") { (k, v) -> "■ $k (${v.size})\n  " + v.distinct().take(6).joinToString("\n  ") }
            fail("재생 테스트 문제 ${problems.size}건\n$text")
        }
    }
}
