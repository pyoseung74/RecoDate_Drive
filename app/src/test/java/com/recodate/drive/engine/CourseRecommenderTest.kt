package com.recodate.drive.engine

import com.recodate.drive.data.DemoData
import com.recodate.drive.model.Anchor
import com.recodate.drive.model.CongestionLevel
import com.recodate.drive.model.CourseMode
import com.recodate.drive.model.CourseRequest
import com.recodate.drive.model.DataSource
import com.recodate.drive.model.GeoPoint
import com.recodate.drive.model.Interest
import com.recodate.drive.model.OccupancySource
import com.recodate.drive.model.ParkingStatus
import com.recodate.drive.model.VehicleEnergy
import com.recodate.drive.model.Weather
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseRecommenderTest {

    private val anmok = DemoData.recentAnchors.first() // 안목해변 커피거리
    private val gangneungStation = GeoPoint(37.7637, 128.8995)
    private val sunny = Weather(false, 10, 20.0, DataSource.DEMO)
    private val rainy = Weather(true, 90, 14.0, DataSource.DEMO)

    private fun request(
        mode: CourseMode,
        anchor: Anchor = anmok,
        weather: Weather? = sunny,
        interests: Set<Interest> = emptySet(),
        energy: VehicleEnergy = VehicleEnergy.UNKNOWN,
    ) = CourseRequest(
        anchor = anchor, mode = mode, interests = interests, startMinuteOfDay = 13 * 60,
        isWeekend = true, weather = weather, energy = energy, origin = gangneungStation,
    )

    private fun recommender(status: (String) -> ParkingStatus? = { null }) = CourseRecommender { lot ->
        status(lot.id) ?: ParkingPredictor.predict(lot, DemoData.places, 13 * 60, true)
    }

    @Test
    fun parkAndWalk_usesOneParkingLot_andStaysWithinWalkLimits() {
        val courses = recommender().recommend(request(CourseMode.PARK_AND_WALK), DemoData.places, DemoData.parkingLots)
        assertTrue(courses.isNotEmpty())
        courses.forEach { c ->
            assertTrue(c.mainParking != null)
            assertTrue("총 도보 ${c.totalWalkM}", c.totalWalkM <= CourseRecommender.PW_MAX_TOTAL_WALK_M)
            assertTrue(c.stops.all { it.parking == null })
            assertTrue(c.stops.size >= CourseRecommender.MIN_STOPS)
        }
    }

    @Test
    fun anchorPlace_isAlwaysIncluded() {
        CourseMode.entries.forEach { mode ->
            val courses = recommender().recommend(request(mode), DemoData.places, DemoData.parkingLots)
            assertTrue(courses.isNotEmpty())
            courses.forEach { c -> assertTrue("$mode ${c.id}", c.stops.any { it.place.id == anmok.place!!.id }) }
        }
    }

    @Test
    fun driveAndHop_givesEveryStopItsOwnNearbyParking() {
        val courses = recommender().recommend(request(CourseMode.DRIVE_AND_HOP), DemoData.places, DemoData.parkingLots)
        assertTrue(courses.isNotEmpty())
        courses.forEach { c ->
            c.stops.forEach { s ->
                assertTrue(s.parking != null)
                assertTrue(s.walkInM <= CourseRecommender.DH_MAX_PARK_WALK_M)
            }
        }
    }

    @Test
    fun rain_prefersIndoorPlaces() {
        val sunnyCourse = recommender().recommend(request(CourseMode.DRIVE_AND_HOP, weather = sunny), DemoData.places, DemoData.parkingLots)
        val rainCourse = recommender().recommend(request(CourseMode.DRIVE_AND_HOP, weather = rainy), DemoData.places, DemoData.parkingLots)
        fun outdoor(list: List<com.recodate.drive.model.Course>) = list.sumOf { c -> c.stops.count { !it.place.indoor } }
        assertTrue(outdoor(rainCourse) <= outdoor(sunnyCourse))
        assertTrue(rainCourse.first().reasons.any { "비" in it })
    }

    @Test
    fun fullParkingLot_isAvoided_whenAlternativesExist() {
        val full = ParkingStatus(0.97, OccupancySource.REALTIME)
        val courses = recommender { id -> if (id == "pk01") full else null }
            .recommend(request(CourseMode.PARK_AND_WALK), DemoData.places, DemoData.parkingLots)
        assertTrue(courses.isNotEmpty())
        assertTrue(courses.none { it.mainParking?.id == "pk01" })
        assertEquals(CongestionLevel.FULL, full.level)
    }

    @Test
    fun coursesAreDifferentFromEachOther() {
        val courses = recommender().recommend(request(CourseMode.DRIVE_AND_HOP), DemoData.places, DemoData.parkingLots)
        if (courses.size >= 2) assertNotEquals(courses[0].id, courses[1].id)
    }

    @Test
    fun lowBattery_addsChargerWarning() {
        val courses = recommender().recommend(
            request(CourseMode.PARK_AND_WALK, energy = VehicleEnergy(10, null)), DemoData.places, DemoData.parkingLots,
        )
        assertTrue(courses.first().warnings.any { "배터리 10%" in it && "충전소" in it })
    }

    @Test
    fun myArea_withoutPlace_isOnlyACenterPoint() {
        val here = Anchor("지금 내 주변", GeoPoint(37.7720, 128.9475))
        val courses = recommender().recommend(request(CourseMode.PARK_AND_WALK, anchor = here), DemoData.places, DemoData.parkingLots)
        assertTrue(courses.isNotEmpty())
        assertFalse(courses.any { c -> c.stops.any { it.place.id == "anchor" } })
    }

    @Test
    fun kmaGrid_matchesKnownPoints() {
        // 기상청 예시: 서울시청 (60, 127)
        assertEquals(60 to 127, com.recodate.drive.data.KmaWeatherSource.toGrid(37.5665, 126.9780))
    }
}
