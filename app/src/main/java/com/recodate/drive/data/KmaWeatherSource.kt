package com.recodate.drive.data

import com.recodate.drive.model.DataSource
import com.recodate.drive.model.GeoPoint
import com.recodate.drive.model.Weather
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * 기상청 단기예보 (공공데이터포털 VilageFcstInfoService_2.0/getVilageFcst).
 * 5km 격자 예보. 발표 시각: 02, 05, 08, 11, 14, 17, 20, 23시 (+10분 후 제공).
 * PTY(강수형태, 0=없음), POP(강수확률 %), TMP(기온 ℃)를 쓴다.
 */
class KmaWeatherSource(private val serviceKey: String) : WeatherSource {

    private val zone = ZoneId.of("Asia/Seoul")
    private val cache = mutableMapOf<String, Pair<Long, List<Fcst>>>()

    private data class Fcst(val dateTime: LocalDateTime, val category: String, val value: String)

    override suspend fun forecast(at: GeoPoint, minuteOfDay: Int): Weather? = runCatching {
        val (nx, ny) = toGrid(at.lat, at.lon)
        val now = LocalDateTime.now(zone)
        val (baseDate, baseTime) = baseDateTime(now)
        val key = "$nx,$ny,$baseDate$baseTime"
        val items = cache[key]?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000 }?.second
            ?: fetch(nx, ny, baseDate, baseTime).also { cache[key] = System.currentTimeMillis() to it }

        // 방문 예정 시각(오늘, 이미 지났으면 다음 날)에 가장 가까운 예보 시각
        var target = now.toLocalDate().atStartOfDay().plusMinutes(minuteOfDay.toLong())
        if (target.isBefore(now.minusHours(1))) target = target.plusDays(1)
        val times = items.map { it.dateTime }.distinct()
        val nearest = times.minByOrNull { abs(java.time.Duration.between(it, target).toMinutes()) } ?: return@runCatching null
        val at0 = items.filter { it.dateTime == nearest }.associate { it.category to it.value }

        Weather(
            precipitating = (at0["PTY"]?.toIntOrNull() ?: 0) != 0,
            rainProbability = at0["POP"]?.toIntOrNull(),
            temperatureC = at0["TMP"]?.toDoubleOrNull(),
            source = DataSource.LIVE,
        )
    }.onFailure { android.util.Log.w("Kma", "forecast failed: $it") }.getOrNull()

    private suspend fun fetch(nx: Int, ny: Int, baseDate: String, baseTime: String): List<Fcst> {
        // "Encoding" 키(%2F 등 포함)를 넣었으면 먼저 풀고, 한 번만 인코딩한다
        val raw = if ('%' in serviceKey) URLDecoder.decode(serviceKey, "UTF-8") else serviceKey
        val url = "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst" +
            "?serviceKey=${URLEncoder.encode(raw, "UTF-8")}" +
            "&pageNo=1&numOfRows=600&dataType=JSON&base_date=$baseDate&base_time=$baseTime&nx=$nx&ny=$ny"
        val root = Http.getJson(url)
        val fmt = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
        return root.jsonObject["response"]!!.jsonObject["body"]!!.jsonObject["items"]!!.jsonObject["item"]!!
            .jsonArray.map { it.jsonObject }
            .filter { it["category"]?.jsonPrimitive?.content in setOf("PTY", "POP", "TMP") }
            .map {
                Fcst(
                    LocalDateTime.parse(it["fcstDate"]!!.jsonPrimitive.content + it["fcstTime"]!!.jsonPrimitive.content, fmt),
                    it["category"]!!.jsonPrimitive.content,
                    it["fcstValue"]!!.jsonPrimitive.content,
                )
            }
    }

    companion object {
        /** 지금 받을 수 있는 가장 최근 발표 시각 */
        fun baseDateTime(now: LocalDateTime): Pair<String, String> {
            val slots = listOf(2, 5, 8, 11, 14, 17, 20, 23)
            val ready = now.minusMinutes(10)
            val hour = slots.lastOrNull { it <= ready.hour }
            val date = if (hour == null) ready.toLocalDate().minusDays(1) else ready.toLocalDate()
            return date.format(DateTimeFormatter.BASIC_ISO_DATE) to "%02d00".format(hour ?: 23)
        }

        /** 위경도 → 기상청 격자(nx, ny). 기상청 공식 람베르트 정각원추도법 변환 */
        fun toGrid(lat: Double, lon: Double): Pair<Int, Int> {
            val re = 6371.00877 / 5.0
            val deg = PI / 180.0
            val slat1 = 30.0 * deg
            val slat2 = 60.0 * deg
            val olon = 126.0 * deg
            val olat = 38.0 * deg
            var sn = tan(PI * 0.25 + slat2 * 0.5) / tan(PI * 0.25 + slat1 * 0.5)
            sn = ln(cos(slat1) / cos(slat2)) / ln(sn)
            var sf = tan(PI * 0.25 + slat1 * 0.5)
            sf = sf.pow(sn) * cos(slat1) / sn
            var ro = tan(PI * 0.25 + olat * 0.5)
            ro = re * sf / ro.pow(sn)
            var ra = tan(PI * 0.25 + lat * deg * 0.5)
            ra = re * sf / ra.pow(sn)
            var theta = lon * deg - olon
            if (theta > PI) theta -= 2.0 * PI
            if (theta < -PI) theta += 2.0 * PI
            theta *= sn
            val x = floor(ra * sin(theta) + 43 + 0.5).toInt()
            val y = floor(ro - ra * cos(theta) + 136 + 0.5).toInt()
            return x to y
        }
    }
}
