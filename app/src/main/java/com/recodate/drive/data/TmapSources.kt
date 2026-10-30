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

/**
 * POI 결과 캐시 (30분). 같은 지역을 다시 찾거나 "다른 코스"를 누를 때 TMAP을 다시 부르지 않는다.
 * 중심 좌표는 소수 셋째 자리(약 100m)로 묶는다.
 */
private object PoiCache {
    private const val TTL_MS = 30 * 60_000L
    private val map = mutableMapOf<String, Pair<Long, List<JsonObject>>>()

    @Synchronized fun get(key: String): List<JsonObject>? =
        map[key]?.takeIf { System.currentTimeMillis() - it.first < TTL_MS }?.second

    @Synchronized fun put(key: String, value: List<JsonObject>) {
        map[key] = System.currentTimeMillis() to value
    }
}

/**
 * 키가 거부되면(401/403) 30분 동안 TMAP을 부르지 않는다.
 * 잘못된 키로 호출을 반복해 한도를 낭비하지 않기 위해서다.
 */
internal object TmapKeyBlock {
    private const val BLOCK_MS = 30 * 60_000L
    @Volatile private var blockedUntil = 0L

    val blocked: Boolean get() = System.currentTimeMillis() < blockedUntil

    fun onError(e: Throwable) {
        if (e is HttpError && (e.code == 401 || e.code == 403)) {
            blockedUntil = System.currentTimeMillis() + BLOCK_MS
            android.util.Log.w("Tmap", "API key rejected (${e.code}) → TMAP calls paused for 30 min")
        }
    }
}

/** 모든 TMAP POI 호출은 이 함수만 거친다: 캐시 → 키 차단 확인 → 한도 확인(차감) → 호출 */
private suspend fun tmapAround(
    appKey: String,
    quota: TmapQuota,
    center: GeoPoint,
    radiusM: Int,
    query: String,
    count: Int,
): List<JsonObject> {
    val radiusKm = ceil(radiusM / 1000.0).toInt().coerceIn(1, 33)
    val cacheKey = "%s|%.3f|%.3f|%d|%d".format(query, center.lat, center.lon, radiusKm, count)
    PoiCache.get(cacheKey)?.let { return it }
    if (TmapKeyBlock.blocked) error("TMAP key rejected recently")
    if (!quota.tryAcquire(TmapApi.POI)) throw TmapQuotaExceeded(TmapApi.POI)
    val url = "$TMAP/tmap/pois/search/around?version=1&format=json" +
        "&centerLat=${center.lat}&centerLon=${center.lon}" +
        "&categories=${URLEncoder.encode(query, "UTF-8")}" +
        "&radius=$radiusKm&count=$count&reqCoordType=WGS84GEO&resCoordType=WGS84GEO"
    val root = try {
        Http.getJson(url, mapOf("appKey" to appKey, "Accept" to "application/json"))
    } catch (e: Exception) {
        TmapKeyBlock.onError(e); throw e
    }
    // 검색 결과가 0건이면 TMAP이 본문 없이 응답할 수 있다 → 빈 목록
    val pois = root.jsonObject["searchPoiInfo"]?.jsonObject?.get("pois")?.jsonObject?.get("poi")
        ?.jsonArray?.map { it.jsonObject } ?: emptyList()
    PoiCache.put(cacheKey, pois)
    return pois
}

private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content.orEmpty()

private fun JsonObject.point(): GeoPoint? {
    // frontLat/Lon = 입구 좌표, noorLat/Lon = 중심 좌표
    val lat = this["frontLat"]?.jsonPrimitive?.doubleOrNull ?: this["noorLat"]?.jsonPrimitive?.doubleOrNull
    val lon = this["frontLon"]?.jsonPrimitive?.doubleOrNull ?: this["noorLon"]?.jsonPrimitive?.doubleOrNull
    return if (lat != null && lon != null) GeoPoint(lat, lon) else null
}

private fun isParkingPoi(poi: JsonObject): Boolean {
    val biz = poi.str("middleBizName") + " " + poi.str("lowerBizName") + " " + poi.str("detailBizName")
    return isParkingName(poi.str("name")) || "주차" in biz
}

private fun JsonObject.address(): String =
    listOf(str("upperAddrName"), str("middleAddrName"), str("lowerAddrName"), str("roadName"))
        .filter { it.isNotBlank() }.joinToString(" ")

class TmapPlaceSource(private val appKey: String, private val quota: TmapQuota) : PlaceSource {
    override suspend fun nearby(center: GeoPoint, radiusM: Int, categories: Set<PlaceCategory>): List<Place> =
        coroutineScope {
            categories.mapNotNull { cat -> CATEGORY_QUERY[cat]?.let { q -> cat to q } }
                .map { (cat, q) ->
                    async {
                        runCatching { tmapAround(appKey, quota, center, radiusM, q, 30) }
                            .onFailure { android.util.Log.w("Tmap", "POI '$q' failed: $it") }
                            .getOrDefault(emptyList())
                            .mapNotNull { poi ->
                                // 장소 검색 결과에 섞여 오는 주차장은 뺀다 (주차장은 따로 검색한다)
                                if (isParkingPoi(poi)) return@mapNotNull null
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

class TmapParkingSource(private val appKey: String, private val quota: TmapQuota) : ParkingSource {
    override suspend fun nearby(center: GeoPoint, radiusM: Int): List<ParkingLot> =
        tmapAround(appKey, quota, center, radiusM, "주차장", 50).mapNotNull { poi ->
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

class TmapTrafficSource(private val appKey: String, private val quota: TmapQuota) : TrafficSource {
    private val cache = mutableMapOf<String, Pair<Long, Int>>()

    override suspend fun driveMinutes(from: GeoPoint, to: GeoPoint): Int? {
        // 출발지는 약 1km 단위로 묶어 캐시를 더 자주 쓴다 (도착 시간 오차는 1~2분)
        val key = "%.2f,%.2f>%.4f,%.4f".format(from.lat, from.lon, to.lat, to.lon)
        synchronized(cache) {
            cache[key]?.let { (t, v) -> if (System.currentTimeMillis() - t < 10 * 60_000) return v }
        }
        // 키가 거부됐거나 한도에 걸리면 호출하지 않고 null → 앱은 예상 시간을 쓴다
        if (TmapKeyBlock.blocked) return null
        if (!quota.tryAcquire(TmapApi.ROUTE)) return null
        val body = """{"startX":"${from.lon}","startY":"${from.lat}","endX":"${to.lon}","endY":"${to.lat}",""" +
            """"reqCoordType":"WGS84GEO","resCoordType":"WGS84GEO","searchOption":"0","trafficInfo":"Y"}"""
        return runCatching {
            val root: JsonElement = Http.postJson("$TMAP/tmap/routes?version=1&format=json", body, mapOf("appKey" to appKey))
            val sec = root.jsonObject["features"]!!.jsonArray[0].jsonObject["properties"]!!
                .jsonObject["totalTime"]!!.jsonPrimitive.int
            (sec / 60.0).roundToInt().coerceAtLeast(1)
        }.onFailure { TmapKeyBlock.onError(it); android.util.Log.w("Tmap", "route failed: $it") }
            .getOrNull()?.also { synchronized(cache) { cache[key] = System.currentTimeMillis() to it } }
    }
}
