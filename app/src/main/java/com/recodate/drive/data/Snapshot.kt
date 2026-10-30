package com.recodate.drive.data

import com.recodate.drive.model.Anchor
import com.recodate.drive.model.CourseMode
import com.recodate.drive.model.DataSource
import com.recodate.drive.model.GeoPoint
import com.recodate.drive.model.ParkingLot
import com.recodate.drive.model.Place
import com.recodate.drive.model.PlaceCategory
import com.recodate.drive.model.Weather
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 추천 1번에 쓴 입력 데이터 묶음 (디버그 빌드에서만 기기에 저장).
 * 같은 데이터로 추천 엔진을 API 호출 없이 반복 테스트하는 데 쓴다.
 * TMAP 데이터가 들어 있으므로 저장소(git)에는 올리지 않는다.
 */
data class Snapshot(
    val label: String,
    val anchor: Anchor,
    val mode: CourseMode,
    val startMinuteOfDay: Int,
    val isWeekend: Boolean,
    val weather: Weather?,
    val origin: GeoPoint?,
    val places: List<Place>,
    val lots: List<ParkingLot>,
) {
    fun toJson(): String = buildJsonObject {
        put("label", label)
        put("anchor", buildJsonObject {
            put("name", anchor.name); put("lat", anchor.point.lat); put("lon", anchor.point.lon)
            anchor.place?.let { put("place", placeJson(it)) }
        })
        put("mode", mode.name)
        put("minute", startMinuteOfDay)
        put("weekend", isWeekend)
        weather?.let { w ->
            put("weather", buildJsonObject {
                put("rain", w.precipitating); w.rainProbability?.let { put("pop", it) }; w.temperatureC?.let { put("temp", it) }
            })
        }
        origin?.let { put("origin", buildJsonObject { put("lat", it.lat); put("lon", it.lon) }) }
        put("places", buildJsonArray { places.forEach { add(placeJson(it)) } })
        put("lots", buildJsonArray {
            lots.forEach { l ->
                add(buildJsonObject {
                    put("id", l.id); put("name", l.name); put("lat", l.point.lat); put("lon", l.point.lon)
                    l.capacity?.let { put("cap", it) }; l.feePer30Min?.let { put("fee", it) }; l.isPublic?.let { put("public", it) }
                })
            }
        })
    }.toString()

    companion object {
        private fun placeJson(p: Place) = buildJsonObject {
            put("id", p.id); put("name", p.name); put("cat", p.category.name)
            put("lat", p.point.lat); put("lon", p.point.lon); put("indoor", p.indoor)
            p.popularity?.let { put("pop", it) }
        }

        private fun parsePlace(o: JsonObject) = Place(
            id = o.s("id"), name = o.s("name"), category = PlaceCategory.valueOf(o.s("cat")),
            point = GeoPoint(o.d("lat"), o.d("lon")),
            indoor = o["indoor"]?.jsonPrimitive?.booleanOrNull ?: false,
            popularity = o["pop"]?.jsonPrimitive?.doubleOrNull, source = DataSource.LIVE,
        )

        fun fromJson(text: String): Snapshot {
            val o = Http.json.parseToJsonElement(text).jsonObject
            val a = o["anchor"]!!.jsonObject
            val w = o["weather"]?.jsonObject
            val org = o["origin"]?.jsonObject
            return Snapshot(
                label = o.s("label"),
                anchor = Anchor(a.s("name"), GeoPoint(a.d("lat"), a.d("lon")), a["place"]?.jsonObject?.let(::parsePlace)),
                mode = CourseMode.valueOf(o.s("mode")),
                startMinuteOfDay = o["minute"]!!.jsonPrimitive.int,
                isWeekend = o["weekend"]!!.jsonPrimitive.booleanOrNull ?: false,
                weather = w?.let {
                    Weather(it["rain"]!!.jsonPrimitive.booleanOrNull ?: false, it["pop"]?.jsonPrimitive?.intOrNull,
                        it["temp"]?.jsonPrimitive?.doubleOrNull, DataSource.LIVE)
                },
                origin = org?.let { GeoPoint(it.d("lat"), it.d("lon")) },
                places = o["places"]!!.jsonArray.map { parsePlace(it.jsonObject) },
                lots = o["lots"]!!.jsonArray.map { e ->
                    val l = e.jsonObject
                    ParkingLot(l.s("id"), l.s("name"), GeoPoint(l.d("lat"), l.d("lon")),
                        capacity = l["cap"]?.jsonPrimitive?.intOrNull, feePer30Min = l["fee"]?.jsonPrimitive?.intOrNull,
                        isPublic = l["public"]?.jsonPrimitive?.booleanOrNull, source = DataSource.LIVE)
                },
            )
        }

        private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
        private fun JsonObject.d(k: String) = this[k]!!.jsonPrimitive.double
    }
}
