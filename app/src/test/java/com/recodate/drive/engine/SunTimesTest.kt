package com.recodate.drive.engine

import com.recodate.drive.model.GeoPoint
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SunTimesTest {
    private fun near(actual: Int?, expected: Int, tol: Int = 4) =
        assertTrue("expected ~$expected, got $actual", actual != null && kotlin.math.abs(actual - expected) <= tol)

    @Test fun seoulSunsetMatchesAlmanac() {
        val seoul = GeoPoint(37.5665, 126.9780)
        // 한국천문연구원 기준 서울 일몰: 11/5 17:28, 6/21 19:57, 12/22 17:17
        near(SunTimes.sunsetMinuteKst(seoul, LocalDate.of(2026, 11, 5)), 17 * 60 + 28)
        near(SunTimes.sunsetMinuteKst(seoul, LocalDate.of(2026, 6, 21)), 19 * 60 + 57)
        near(SunTimes.sunsetMinuteKst(seoul, LocalDate.of(2026, 12, 22)), 17 * 60 + 17)
    }

    @Test fun gangneungSetsEarlierThanSeoul() {
        val d = LocalDate.of(2026, 11, 5)
        val gangneung = SunTimes.sunsetMinuteKst(GeoPoint(37.7637, 128.8995), d)!!
        val seoul = SunTimes.sunsetMinuteKst(GeoPoint(37.5665, 126.9780), d)!!
        assertTrue(gangneung < seoul)
    }

    @Test fun polarNightHasNoSunset() {
        assertNull(SunTimes.sunsetMinuteKst(GeoPoint(80.0, 20.0), LocalDate.of(2026, 12, 21)))
    }
}
