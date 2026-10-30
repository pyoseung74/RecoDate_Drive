package com.recodate.drive.data

import com.recodate.drive.model.DataSource
import com.recodate.drive.model.GeoPoint
import com.recodate.drive.model.ParkingLot
import com.recodate.drive.model.Place
import com.recodate.drive.model.PlaceCategory
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * TMAP (SK open API)
 * - 주변 POI 검색: GET /tmap/pois/search/around  (반경 단위 km)
 * - 자동차 경로:   POST /tmap/routes            (trafficInfo=Y → 실시간 교통 반영)
 */
private const val TMAP = "https://apis.openapi.sk.com"

/** 장소 종류 → TMAP 업종 검색어 */
private val CATEGORY_QUERY = mapOf(
    PlaceCategory.FOOD to "음식점",
    PlaceCategory.CAFE to "카페",
    PlaceCategory.WALK to "공원",
    PlaceCategory.ATTRACTION to "관광명소",
    PlaceCategory.INDOOR_ACTIVITY to "문화시설",
    PlaceCategory.EV_CHARGER to "전기차충전소",
    PlaceCategory.GAS_STATION to "주유소",
)

private suspend fun tmapAround(appKey: String, center: GeoPoint, radiusM: Int, query: String, count: Int): List<JsonObject> {
    val radiusKm = ceil(radiusM / 1000.0).toInt().coerceIn(1, 33)
    val url = "$TMAP/tmap/pois/search/around?version=1&format=json" +
        "&centerLat=${center.lat}&centerLon=${center.lon}" +
        "&categories=${URLEncoder.encode(query, "UTF-8")}" +
        "&radius=$radiusKm&count=$count&reqCoordType=WGS84GEO&resCoordType=WGS84GEO"
    val root = Http.getJson(url, mapOf("appKey" to appKey, "Accept" to "application/json"))
    return root.jsonObject["searchPoiInfo"]?.jsonObject?.get("pois")?.jsonObject?.get("poi")
        ?.jsonArray?.map { it.jsonObject } ?: emptyList()
}

private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content.orEmpty()

private fun JsonObject.point(): GeoPoint? {
    // frontLat/Lon = 입구 좌표, noorLat/Lon = 중심 좌표
    val lat = this["frontLat"]?.jsonPrimitive?.doubleOrNull ?: this["noorLat"]?.jsonPrimitive?.doubleOrNull
    val lon = this["frontLon"]?.jsonPrimitive?.doubleOrNull ?: this["noorLon"]?.jsonPrimitive?.doubleOrNull
    return if (lat != null && lon != null) GeoPoint(lat, lon) else null
}

private fun JsonObject.address(): String =
    listOf(str("upperAddrName"), str("middleAddrName"), str("lowerAddrName"), str("roadName"))
        .filter { it.isNotBlank() }.joinToString(" ")

class TmapPlaceSource(private val appKey: String) : PlaceSource {
    override suspend fun nearby(center: GeoPoint, radiusM: Int, categories: Set<PlaceCategory>): List<Place> =
        coroutineScope {
            categories.mapNotNull { cat -> CATEGORY_QUERY[cat]?.let { q -> cat to q } }
                .map { (cat, q) ->
                    async {
                        runCatching { tmapAround(appKey, center, radiusM, q, 30) }.getOrDefault(emptyList())
                            .mapNotNull { poi ->
                                val pt = poi.point() ?: return@mapNotNull null
                                Place(
                                    id = "tm-" + poi.str("id"),
                                    name = poi.str("name"),
                                    category = cat,
                                    point = pt,
                                    indoor = indoorGuess(cat, poi),
                                    address = poi.address(),
                                    popularity = null,
                                    source = DataSource.LIVE,
                                )
                            }
                    }
                }.flatMap { it.await() }.distinctBy { it.id }
        }

    /** 업종 이름으로 실내 여부를 추정한다 (공원·해변·전망대 등은 실외) */
    private fun indoorGuess(cat: PlaceCategory, poi: JsonObject): Boolean {
        val biz = poi.str("middleBizName") + poi.str("lowerBizName") + poi.str("detailBizName") + poi.str("name")
        val outdoorWords = listOf("공원", "해변", "해수욕장", "산", "숲", "전망대", "둘레길", "호수", "광장", "다리")
        return if (outdoorWords.any { it in biz }) false else cat.indoorByDefault
    }
}

class TmapParkingSource(private val appKey: String) : ParkingSource {
    override suspend fun nearby(center: GeoPoint, radiusM: Int): List<ParkingLot> =
        tmapAround(appKey, center, radiusM, "주차장", 50).mapNotNull { poi ->
            val pt = poi.point() ?: return@mapNotNull null
            val name = poi.str("name")
            ParkingLot(
                id = "tm-" + poi.str("id"),
                name = name,
                point = pt,
                capacity = null,
                feePer30Min = null,
                isPublic = name.contains("공영"),
                source = DataSource.LIVE,
            )
        }.distinctBy { it.id }
}

class TmapTrafficSource(private val appKey: String) : TrafficSource {
    private val cache = mutableMapOf<String, Pair<Long, Int>>()

    override suspend fun driveMinutes(from: GeoPoint, to: GeoPoint): Int? {
        val key = "%.4f,%.4f>%.4f,%.4f".format(from.lat, from.lon, to.lat, to.lon)
        cache[key]?.let { (t, v) -> if (System.currentTimeMillis() - t < 5 * 60_000) return v }
        val body = """{"startX":"${from.lon}","startY":"${from.lat}","endX":"${to.lon}","endY":"${to.lat}",""" +
            """"reqCoordType":"WGS84GEO","resCoordType":"WGS84GEO","searchOption":"0","trafficInfo":"Y"}"""
        return runCatching {
            val root: JsonElement = Http.postJson("$TMAP/tmap/routes?version=1&format=json", body, mapOf("appKey" to appKey))
            val sec = root.jsonObject["features"]!!.jsonArray[0].jsonObject["properties"]!!
                .jsonObject["totalTime"]!!.jsonPrimitive.int
            (sec / 60.0).roundToInt().coerceAtLeast(1)
        }.getOrNull()?.also { cache[key] = System.currentTimeMillis() to it }
    }
}
