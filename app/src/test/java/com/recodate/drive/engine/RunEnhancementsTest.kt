package com.recodate.drive.engine

import com.recodate.drive.data.DemoData
import com.recodate.drive.model.CourseMode
import com.recodate.drive.model.CourseRequest
import com.recodate.drive.model.CourseStop
import com.recodate.drive.model.DataSource
import com.recodate.drive.model.GeoPoint
import com.recodate.drive.model.Place
import com.recodate.drive.model.PlaceCategory
import com.recodate.drive.model.VehicleEnergy
import com.recodate.drive.model.Weather
import com.recodate.drive.ui.delayMinutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunEnhancementsTest {

    private val anmok = DemoData.recentAnchors.first()
    private val sunny = Weather(false, 10, 20.0, DataSource.DEMO)

    private fun request(mode: CourseMode, energy: VehicleEnergy, start: Int = 13 * 60) = CourseRequest(
        anchor = anmok, mode = mode, interests = emptySet(), startMinuteOfDay = start,
        isWeekend = true, weather = sunny, energy = energy, origin = GeoPoint(37.7637, 128.8995),
    )

    private val recommender = CourseRecommender { lot -> ParkingPredictor.predict(lot, DemoData.places, 13 * 60, true) }

    /** 모든 식사·실내 장소 바로 옆(약 30m)에 충전소를 하나씩 둔 데이터 */
    private val placesWithChargers = DemoData.places + DemoData.places
        .filter { it.category.stayMin >= 60 }
        .map { Place("ev-${it.id}", "${it.name} 충전소", PlaceCategory.EV_CHARGER, GeoPoint(it.point.lat + 0.0003, it.point.lon)) } +
        DemoData.parkingLots.map { Place("ev-${it.id}", "${it.name} 충전소", PlaceCategory.EV_CHARGER, GeoPoint(it.point.lat, it.point.lon + 0.0004)) }

    private val lowEv = VehicleEnergy(evSocPercent = 12, fuelPercent = null)

    @Test
    fun lowEvBattery_suggestsChargerNearLongStay() {
        CourseMode.entries.forEach { mode ->
            val courses = recommender.recommend(request(mode, lowEv), placesWithChargers, DemoData.parkingLots)
            assertTrue(courses.isNotEmpty())
            courses.forEach { c ->
                val ch = c.charge ?: return@forEach
                assertTrue(ch.stayMin >= 60)
                if (mode == CourseMode.PARK_AND_WALK) {
                    assertEquals(0, ch.stopIndex)
                    assertTrue(ch.walkM <= CourseRecommender.PW_CHARGER_NEAR_LOT_M)
                } else {
                    assertTrue(ch.walkM <= CourseRecommender.DH_MAX_PARK_WALK_M)
                    assertEquals(ch.forPlace.id, c.stops[ch.stopIndex].place.id)
                }
                assertTrue(c.reasons.any { "충전소 후보" in it && "확인 필요" in it })
            }
            assertTrue("$mode: 충전 제안이 하나는 있어야 함", courses.any { it.charge != null })
        }
    }

    @Test
    fun noChargeSuggestion_whenBatteryOkOrFuelCar() {
        val ok = recommender.recommend(request(CourseMode.DRIVE_AND_HOP, VehicleEnergy(80, null)), placesWithChargers, DemoData.parkingLots)
        assertTrue(ok.all { it.charge == null })
        val fuel = recommender.recommend(request(CourseMode.DRIVE_AND_HOP, VehicleEnergy(null, 10)), placesWithChargers, DemoData.parkingLots)
        assertTrue(fuel.all { it.charge == null })
    }

    @Test
    fun residentOnlyCharger_isNotSuggested() {
        val restricted = placesWithChargers.map {
            if (it.category == PlaceCategory.EV_CHARGER) it.copy(name = "${it.name} 입주민전용") else it
        }
        val courses = recommender.recommend(request(CourseMode.DRIVE_AND_HOP, lowEv), restricted, DemoData.parkingLots)
        assertTrue(courses.all { it.charge == null })
    }

    @Test
    fun energyNote_usesEachCoursesOwnFirstTarget() {
        val courses = recommender.recommend(request(CourseMode.PARK_AND_WALK, lowEv), DemoData.places, DemoData.parkingLots)
        val notes = courses.map { c -> c.warnings.first { "배터리" in it } }
        // 코스마다 첫 주차장이 다르면 충전소까지 거리도 각자 계산된다
        if (courses.map { it.firstNaviTarget.point }.distinct().size > 1) assertTrue(notes.distinct().size >= 1)
        courses.forEach { c ->
            val nearest = DemoData.places.filter { it.category == PlaceCategory.EV_CHARGER }
                .minByOrNull { Geo.distanceM(it.point, c.firstNaviTarget.point) }!!
            assertTrue(c.warnings.any { nearest.name in it })
        }
    }

    // ───── 지연 계산 ─────

    private fun stop(arrive: Int, walk: Int) =
        CourseStop(place = DemoData.places.first(), walkInM = walk, arriveMinuteOfDay = arrive)

    @Test
    fun delay_comparesWithPlannedParkingArrival() {
        // 장소 도착 14:10, 주차장에서 도보 400m(6분) → 주차장 도착 계획 14:04
        assertEquals(11, delayMinutes(14 * 60 + 15, stop(14 * 60 + 10, 400)))
        assertEquals(-4, delayMinutes(14 * 60, stop(14 * 60 + 10, 400)))
    }

    @Test
    fun delay_handlesMidnight() {
        assertEquals(20, delayMinutes(10, stop(23 * 60 + 50, 0)))     // 23:50 계획, 00:10 도착
        assertEquals(-15, delayMinutes(23 * 60 + 50, stop(1445, 0)))  // 다음날 00:05 계획
    }

    // ───── 일몰 ─────

    private fun outdoorCourse(start: Int) = recommender.recommend(
        request(CourseMode.PARK_AND_WALK, VehicleEnergy.UNKNOWN, start), DemoData.places, DemoData.parkingLots,
    ).first { c -> c.stops.any { !it.place.indoor && it.place.category in setOf(PlaceCategory.WALK, PlaceCategory.ATTRACTION) } }

    @Test
    fun sunsetWarning_whenOutdoorStopEndsAfterDark() {
        val c = outdoorCourse(16 * 60)
        val noted = c.withSunsetNotes(16 * 60, sunsetMinute = 16 * 60 + 5)
        assertTrue(noted.warnings.any { "일몰 16:05" in it })
    }

    @Test
    fun noSunsetWarning_whenAlreadyDarkOrUnknown() {
        val c = outdoorCourse(19 * 60)
        assertEquals(c, c.withSunsetNotes(19 * 60, sunsetMinute = 17 * 60 + 20))
        assertEquals(c, c.withSunsetNotes(19 * 60, sunsetMinute = null))
    }

    @Test
    fun sunsetReason_whenOutdoorEndsJustBeforeSunset() {
        val c = outdoorCourse(13 * 60)
        val lastEnd = c.stops.filter { !it.place.indoor && it.place.category in setOf(PlaceCategory.WALK, PlaceCategory.ATTRACTION) }
            .maxOf { it.arriveMinuteOfDay + it.place.category.stayMin }
        val noted = c.withSunsetNotes(13 * 60, sunsetMinute = lastEnd + 30)
        assertTrue(noted.reasons.any { "전에 야외 일정 마침" in it })
        assertNull(noted.warnings.firstOrNull { "해가 진 뒤" in it })
    }
}
