package com.recodate.drive.engine

import com.recodate.drive.model.Course
import com.recodate.drive.model.GeoPoint
import com.recodate.drive.model.PlaceCategory
import com.recodate.drive.model.toClock
import java.time.LocalDate
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 해 지는 시각을 인터넷 없이 계산한다 (NOAA 근사식, 오차 1~2분).
 * 결과는 한국 시간(UTC+9) 기준 자정부터의 분. 백야·극야처럼 해가 안 지거나 안 뜨는 날은 null.
 */
object SunTimes {
    private const val KST_OFFSET_MIN = 9 * 60

    fun sunsetMinuteKst(at: GeoPoint, date: LocalDate): Int? {
        val n = date.dayOfYear
        val gamma = 2 * Math.PI / 365.0 * (n - 1)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
            0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
            0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) -
            0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
        val lat = Math.toRadians(at.lat)
        // 해가 지평선 아래 0.833도 (대기 굴절 + 해 반지름)
        val cosHa = cos(Math.toRadians(90.833)) / (cos(lat) * cos(decl)) - Math.tan(lat) * Math.tan(decl)
        if (cosHa < -1.0 || cosHa > 1.0) return null
        val ha = Math.toDegrees(acos(cosHa))
        val utcMin = 720 - 4 * (at.lon - ha) - eqTime
        return (utcMin + KST_OFFSET_MIN).roundToInt()
    }
}

/**
 * 해가 진 뒤에 끝나는 야외 장소(산책·관광, 실외)를 경고한다. 코스 순서·점수는 바꾸지 않는다.
 * 출발 시각이 이미 일몰 뒤면 경고하지 않는다 (어두운 줄 알고 나가는 경우).
 * 일몰 전에 야외 일정이 모두 끝나고 일몰이 가까우면(90분 이내) 이유에 "일몰 전에 마침"을 붙인다.
 */
fun Course.withSunsetNotes(startMinuteOfDay: Int, sunsetMinute: Int?): Course {
    if (sunsetMinute == null || startMinuteOfDay >= sunsetMinute) return this
    val outdoor = stops.filter {
        !it.place.indoor && (it.place.category == PlaceCategory.WALK ||
            it.place.category == PlaceCategory.ATTRACTION)
    }
    if (outdoor.isEmpty()) return this
    val sunset = sunsetMinute.toClock()
    val late = outdoor.filter { it.arriveMinuteOfDay + it.place.category.stayMin > sunsetMinute }
    if (late.isNotEmpty()) {
        val names = late.joinToString(", ") { it.place.name }
        return copy(warnings = warnings + "해가 진 뒤에 끝나는 야외 장소: $names (일몰 $sunset)")
    }
    val lastEnd = outdoor.maxOf { it.arriveMinuteOfDay + it.place.category.stayMin }
    return if (sunsetMinute - lastEnd <= 90) copy(reasons = reasons + "일몰 $sunset 전에 야외 일정 마침") else this
}
