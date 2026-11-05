package com.recodate.drive.engine

import com.recodate.drive.model.CongestionLevel
import com.recodate.drive.model.Course
import com.recodate.drive.model.CourseMode
import com.recodate.drive.model.CourseRequest
import com.recodate.drive.model.CourseStop
import com.recodate.drive.model.GeoPoint
import com.recodate.drive.model.OccupancySource
import com.recodate.drive.model.ParkingLot
import com.recodate.drive.model.ParkingStatus
import com.recodate.drive.model.Place
import com.recodate.drive.model.PlaceCategory
import com.recodate.drive.model.PlaceCategory.ATTRACTION
import com.recodate.drive.model.PlaceCategory.CAFE
import com.recodate.drive.model.PlaceCategory.EV_CHARGER
import com.recodate.drive.model.PlaceCategory.FOOD
import com.recodate.drive.model.PlaceCategory.GAS_STATION
import com.recodate.drive.model.PlaceCategory.INDOOR_ACTIVITY
import com.recodate.drive.model.PlaceCategory.WALK
import com.recodate.drive.model.isResidentOnlyParking
import com.recodate.drive.model.toClock
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 코스 추천 엔진 (Android 의존 없음).
 *
 * 점수는 모두 0~1로 맞춘 뒤 가중합한다.
 * - 장소 점수   = 0.5 관심사 일치 + 0.3 날씨 적합 + 0.2 인기도(모르면 0.5)
 * - 주차장 점수 = 0.6 (1 − 혼잡도, 모르면 0.5) + 0.25 가까움 + 0.15 요금(모르면 0.5)
 * - 코스 점수   = 0.35 장소 평균 + 0.35 주차장 + 0.3 시간 적합 − 0.1 도보 부담 − 혼잡 감점
 *   (혼잡 감점: "혼잡" 주차장 0.1, "매우 혼잡" 0.25. 주차가 이 앱의 핵심이라 확실히 반영한다)
 * 같은 점수면 총 이동이 짧은 코스, 그다음 코스 id 순.
 */
class CourseRecommender(
    /** 주차장 혼잡도. 실시간 정보가 있으면 그것을, 없으면 예상값을 돌려준다 */
    private val parkingStatusOf: (ParkingLot) -> ParkingStatus,
) {

    companion object {
        const val PW_SEARCH_RADIUS_M = 1_000.0    // 한 번 주차형: 앵커에서 주차장 후보 반경
        const val PW_MAX_LEG_WALK_M = 700          // 주차장 → 장소 최대 도보
        const val PW_MAX_TOTAL_WALK_M = 2_000      // 총 도보 상한
        const val DH_SEARCH_RADIUS_M = 10_000.0   // 차로 이동형: 장소 후보 반경
        const val DH_MAX_PARK_WALK_M = 260         // 장소 ↔ 주차장 최대 도보
        const val MIN_STOPS = 2
        /** 조건에 맞는 코스가 하나도 없을 때 도보 제한을 늘리는 배수 (시골·자연 목적지 대비) */
        const val RELAX_FACTOR = 1.7
        /** 차로 이동형에서 목적지 근처 주차장을 찾는 최대 도보 (보통 260m보다 넓게) */
        const val DH_ANCHOR_MAX_PARK_WALK_M = 700
        /** 주차장 정보가 없을 때 목적지 자체를 주차 지점으로 쓰는 가상 주차장 id */
        const val NO_LOT_ID = "no-lot"
        private const val OVERLAP_PENALTY = 0.3
    }

    fun recommend(
        request: CourseRequest,
        places: List<Place>,
        lots: List<ParkingLot>,
        variation: Int = 0,
    ): List<Course> {
        val visitable = places.filter { it.category != EV_CHARGER && it.category != GAS_STATION }
        // 방문객이 쓸 수 없는 주차장(거주자 우선·아파트·전용 주차장 등)은 후보에서 뺀다
        val usableLots = lots.filterNot { isResidentOnlyParking(it.name) }
        fun build(relax: Double): List<Course> {
            val ctx = Ctx(request, visitable, usableLots, variation, relax)
            return when (request.mode) {
                CourseMode.PARK_AND_WALK -> parkAndWalk(ctx)
                CourseMode.DRIVE_AND_HOP -> driveAndHop(ctx)
            }
        }
        // 1단계: 기본 조건. 2단계: 하나도 없으면 도보 제한을 늘려서 다시 (화면에 표시)
        // 3단계(한 번 주차형): 주차장 데이터가 없는 곳(시골·자연 명소)이면 목적지로 바로 안내하고 그 주변을 걷는 코스
        val courses = build(1.0).ifEmpty {
            build(RELAX_FACTOR).map {
                it.copy(relaxedLimits = true, warnings = it.warnings + "주변에 조건에 맞는 곳이 적어 걷는 거리를 늘렸어요")
            }
        }.ifEmpty {
            if (request.mode != CourseMode.PARK_AND_WALK) return@ifEmpty emptyList()
            val atDestination = ParkingLot(NO_LOT_ID, request.anchor.name, request.anchor.point)
            parkAndWalk(Ctx(request, visitable, listOf(atDestination), variation)).map {
                it.copy(warnings = it.warnings + "주차장 정보가 없어 목적지로 안내해요. 도착 후 근처에 주차하세요")
            }
        }
        val energyNotes = energyNotes(request, places, courses)
        return courses
            .map { it.copy(warnings = it.warnings + energyNotes) }
            .sortedWith(compareByDescending<Course> { it.score }
                .thenBy { it.totalWalkM + it.totalDriveMin * 100 }
                .thenBy { it.id })
            .take(request.courseCount)
    }

    // ───────────────────────── 공통 ─────────────────────────

    private inner class Ctx(
        val req: CourseRequest,
        val places: List<Place>,
        val lots: List<ParkingLot>,
        val variation: Int,
        /** 도보 제한 배수 (기본 1.0, 완화 단계에서 RELAX_FACTOR) */
        val relax: Double = 1.0,
    ) {
        val legLimit = (PW_MAX_LEG_WALK_M * relax).toInt()
        val totalLimit = (PW_MAX_TOTAL_WALK_M * relax).toInt()
        val parkWalkLimit = (DH_MAX_PARK_WALK_M * relax).toInt()
        val harsh = req.weather?.isHarsh == true
        val wanted: Set<PlaceCategory> = req.interests.flatMap { it.categories }.toSet()
        val used = mutableMapOf<String, Int>()
        val statusCache = mutableMapOf<String, ParkingStatus>()
        fun status(lot: ParkingLot) = statusCache.getOrPut(lot.id) {
            if (lot.id == NO_LOT_ID) ParkingStatus(null, OccupancySource.UNKNOWN) else parkingStatusOf(lot)
        }

        /** 새로고침마다 달라지는 작은 흔들림 (0 ~ 0.08), 같은 variation이면 같은 결과 */
        fun jitter(id: String): Double =
            if (variation == 0) 0.0 else ((id.hashCode() * 31 + variation * 7919) and 0xFFFF) / 65535.0 * 0.08
    }

    private fun placeScore(ctx: Ctx, p: Place): Double {
        val fit = when {
            ctx.wanted.isEmpty() -> 0.6
            p.category in ctx.wanted -> 1.0
            else -> 0.3
        }
        val weatherFit = if (ctx.req.weather == null || !ctx.harsh) 1.0 else if (p.indoor) 1.0 else 0.2
        val pop = p.popularity ?: 0.5
        return 0.5 * fit + 0.3 * weatherFit + 0.2 * pop + ctx.jitter(p.id)
    }

    private fun parkingScore(ctx: Ctx, lot: ParkingLot, walkM: Int, maxWalkM: Int): Double {
        val st = ctx.status(lot)
        val occ = st.occupancy?.let { 1 - it } ?: 0.5
        val near = 1 - min(1.0, walkM.toDouble() / maxWalkM)
        val fee = lot.feePer30Min?.let { 1 - min(it, 3000) / 3000.0 } ?: 0.5
        return 0.6 * occ + 0.25 * near + 0.15 * fee
    }

    /** 혼잡한 주차장을 쓰는 코스 감점 */
    private fun congestionPenalty(st: ParkingStatus?): Double = when (st?.level) {
        CongestionLevel.BUSY -> 0.1
        CongestionLevel.FULL -> 0.25
        else -> 0.0
    }

    /** 시간대별 코스 틀. 각 칸은 들어갈 수 있는 종류의 집합 */
    private fun template(ctx: Ctx): List<Set<PlaceCategory>> {
        val h = ctx.req.startMinuteOfDay / 60
        val outdoor = if (ctx.harsh) setOf(INDOOR_ACTIVITY, CAFE) else setOf(WALK, ATTRACTION)
        val activity = if (ctx.harsh) setOf(INDOOR_ACTIVITY) else setOf(ATTRACTION, INDOOR_ACTIVITY, WALK)
        val base = when {
            h < 11 -> listOf(setOf(CAFE), outdoor, setOf(FOOD))
            h < 14 -> listOf(setOf(FOOD), setOf(CAFE), outdoor)
            h < 17 -> listOf(activity, setOf(CAFE), setOf(FOOD))
            h < 21 -> listOf(setOf(FOOD), outdoor, setOf(CAFE))
            else -> listOf(setOf(FOOD, CAFE), outdoor)
        }
        // 고른 관심사가 틀에 없으면 가장 덜 맞는 칸을 관심사로 바꾼다
        if (ctx.wanted.isEmpty() || base.any { slot -> slot.any { it in ctx.wanted } }) return base
        return base.dropLast(1) + listOf(ctx.wanted)
    }

    /** 앵커 장소를 넣을 칸 번호. 맞는 칸이 없으면 -1 (맨 앞에 추가) */
    private fun anchorSlot(slots: List<Set<PlaceCategory>>, anchor: Place?): Int =
        if (anchor == null) -1 else slots.indexOfFirst { anchor.category in it }

    // ───────────────────────── 한 번 주차 (도보형) ─────────────────────────

    private fun parkAndWalk(ctx: Ctx): List<Course> {
        val anchor = ctx.req.anchor
        val anchorPlace = anchor.place
        val center = anchor.point
        val maxLotWalk = if (anchorPlace != null) ctx.legLimit else (PW_SEARCH_RADIUS_M * ctx.relax).toInt()

        val lotCandidates = ctx.lots
            .map { it to Geo.walkM(it.point, center) }
            .filter { (_, w) -> w <= maxLotWalk * Geo.WALK_DETOUR }
            .map { (lot, w) -> Triple(lot, w, parkingScore(ctx, lot, w, maxLotWalk)) }
        val notFull = lotCandidates.filter { ctx.status(it.first).level != CongestionLevel.FULL }
        val ranked = (notFull.ifEmpty { lotCandidates }).sortedByDescending { it.third }.take(5)

        val result = mutableListOf<Course>()
        for ((lot, _, lotScore) in ranked) {
            val course = buildParkAndWalk(ctx, lot, lotScore, anchorPlace) ?: continue
            result += course
            course.stops.forEach { ctx.used.merge(it.place.id, 1, Int::plus) }
            if (result.size >= ctx.req.courseCount + 1) break
        }
        return result
    }

    private fun buildParkAndWalk(ctx: Ctx, lot: ParkingLot, lotScore: Double, anchorPlace: Place?): Course? {
        val reachable = ctx.places.filter { Geo.walkM(lot.point, it.point) <= ctx.legLimit }
        if (anchorPlace != null && Geo.walkM(lot.point, anchorPlace.point) > ctx.legLimit) return null

        val slots = template(ctx)
        val aSlot = anchorSlot(slots, anchorPlace)
        val chosen = mutableListOf<Place>()
        if (anchorPlace != null) chosen += anchorPlace

        var prev = anchorPlace?.point ?: lot.point
        slots.forEachIndexed { i, slot ->
            if (i == aSlot) return@forEachIndexed
            val pick = reachable
                .filter { it.category in slot && chosen.none { c -> c.id == it.id } }
                .maxByOrNull {
                    placeScore(ctx, it) -
                        0.25 * Geo.walkM(prev, it.point) / ctx.legLimit -
                        OVERLAP_PENALTY * (ctx.used[it.id] ?: 0)
                } ?: return@forEachIndexed
            chosen += pick
            prev = pick.point
        }
        if (chosen.size < MIN_STOPS) return null

        // 주차장에서 출발해 돌아오는 가장 짧은 순서. 총 도보가 넘치면 상한 안에 드는 가장 좋은 조합을 고른다.
        val order = fitWithinWalk(lot.point, chosen, anchorPlace?.id, ctx.totalLimit) { placeScore(ctx, it) } ?: return null
        val ordered = order.first
        val totalWalk = order.second

        val status = ctx.status(lot)
        val driveToStart = ctx.req.origin?.let {
            Geo.estimateDriveMin(it, lot.point, ctx.req.startMinuteOfDay, ctx.req.isWeekend)
        }
        var clock = ctx.req.startMinuteOfDay + (driveToStart ?: 0)
        var from = lot.point
        val courseStops = ordered.map { p ->
            val walk = Geo.walkM(from, p.point)
            clock += Geo.walkMin(walk)
            val stop = CourseStop(place = p, walkInM = walk, arriveMinuteOfDay = clock)
            clock += p.category.stayMin
            from = p.point
            stop
        }
        clock += Geo.walkMin(Geo.walkM(from, lot.point))

        val avgPlace = ordered.map { placeScore(ctx, it) }.average()
        val timeFit = 1 - min(1.0, (driveToStart ?: 20) / 60.0)
        val score = 0.35 * avgPlace + 0.35 * lotScore + 0.3 * timeFit -
            0.1 * totalWalk / ctx.totalLimit - congestionPenalty(status)

        return Course(
            id = "pw-${lot.id}-${ordered.joinToString("-") { it.id }}",
            mode = CourseMode.PARK_AND_WALK,
            stops = courseStops,
            mainParking = lot,
            mainParkingStatus = status,
            driveToStartMin = driveToStart,
            driveToStartIsLive = false,
            totalWalkM = totalWalk,
            totalDriveMin = driveToStart ?: 0,
            endMinuteOfDay = clock,
            score = score,
            reasons = commonReasons(ctx) + parkingReason(lot, status) +
                "주차 1번 · 총 도보 약 ${totalWalk}m (${Geo.walkMin(totalWalk)}분)",
            warnings = listOfNotNull(
                if (ordered.size < slots.size + (if (aSlot == -1 && anchorPlace != null) 1 else 0))
                    "주변 장소가 적어 ${ordered.size}곳으로 줄였어요" else null,
                if (status.level == CongestionLevel.FULL) "주차장이 매우 혼잡할 수 있어요" else null,
            ),
        )
    }

    /**
     * 주차장에서 출발해 돌아오는 순환 도보가 상한(2km) 안에 드는 가장 좋은 장소 조합과 순서.
     * 장소가 많아야 5곳이라 모든 부분집합을 따진다 (점수 낮은 것부터 빼는 방식은
     * 가까운 장소를 먼저 버려서, 상한 안에 드는 다른 조합이 있어도 놓칠 수 있다).
     * 고르는 기준: 장소 수 많은 것 → 점수 합 높은 것 → 도보 짧은 것. 꼭 들를 곳(anchorId)은 항상 포함.
     * 최소 장소 수(2곳)로도 안 되면 null.
     */
    internal fun fitWithinWalk(
        start: GeoPoint,
        chosen: List<Place>,
        anchorId: String?,
        limitM: Int = PW_MAX_TOTAL_WALK_M,
        scoreOf: (Place) -> Double,
    ): Pair<List<Place>, Int>? {
        val full = bestLoop(start, chosen)
        if (full.second <= limitM) return full
        val n = chosen.size
        var best: Pair<List<Place>, Int>? = null
        var bestKey: Triple<Int, Double, Int>? = null
        for (mask in 1 until (1 shl n)) {
            val subset = chosen.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
            if (subset.size < MIN_STOPS) continue
            if (anchorId != null && subset.none { it.id == anchorId }) continue
            val loop = bestLoop(start, subset)
            if (loop.second > limitM) continue
            val key = Triple(subset.size, subset.sumOf(scoreOf), -loop.second)
            if (bestKey == null || compareValuesBy(key, bestKey, { it.first }, { it.second }, { it.third }) > 0) {
                best = loop; bestKey = key
            }
        }
        return best
    }

    /** start에서 출발해 모든 장소를 돌고 start로 돌아오는 가장 짧은 순서 (장소 ≤ 5, 완전 탐색) */
    private fun bestLoop(start: GeoPoint, stops: List<Place>): Pair<List<Place>, Int> {
        var best = stops
        var bestLen = Int.MAX_VALUE
        for (perm in permutations(stops)) {
            var len = 0
            var cur = start
            for (p in perm) { len += Geo.walkM(cur, p.point); cur = p.point }
            len += Geo.walkM(cur, start)
            if (len < bestLen) { bestLen = len; best = perm }
        }
        return best to bestLen
    }

    // ───────────────────────── 차로 이동 (도보 최소화) ─────────────────────────

    /** 장소 + 그 장소에 쓸 주차장. lot이 null이면 주차장 정보 없이 장소로 바로 안내 (목적지에만 허용) */
    private data class Hop(val place: Place, val lot: ParkingLot?, val walkM: Int, val lotScore: Double) {
        val naviPoint: GeoPoint get() = lot?.point ?: place.point
    }

    private fun driveAndHop(ctx: Ctx): List<Course> {
        val anchor = ctx.req.anchor
        // 장소마다 가장 좋은 근처 주차장을 붙인다. 주차장이 없는 장소는 뺀다.
        val hops = ctx.places
            .filter { Geo.distanceM(anchor.point, it.point) <= DH_SEARCH_RADIUS_M || it.id == anchor.place?.id }
            .mapNotNull { p -> hopFor(ctx, p, ctx.parkWalkLimit) }
        // 목적지는 반드시 넣는다: 가까운 주차장 → 700m 안 주차장 → 주차장 없이 목적지로 바로
        val anchorHop = anchor.place?.let { ap ->
            hops.firstOrNull { it.place.id == ap.id }
                ?: hopFor(ctx, ap, DH_ANCHOR_MAX_PARK_WALK_M)
                ?: Hop(ap, lot = null, walkM = 0, lotScore = 0.4)
        }

        val result = mutableListOf<Course>()
        repeat(ctx.req.courseCount + 1) {
            val course = buildDriveAndHop(ctx, hops, anchorHop) ?: return@repeat
            if (result.any { it.id == course.id }) return@repeat
            result += course
            course.stops.forEach { ctx.used.merge(it.place.id, 1, Int::plus) }
        }
        return result
    }

    private fun hopFor(ctx: Ctx, p: Place, maxWalkM: Int): Hop? {
        val lots = ctx.lots
            .map { it to Geo.walkM(it.point, p.point) }
            .filter { (_, w) -> w <= maxWalkM }
            .map { (lot, w) -> Hop(p, lot, w, parkingScore(ctx, lot, w, maxWalkM)) }
        val notFull = lots.filter { ctx.status(it.lot!!).level != CongestionLevel.FULL }
        return (notFull.ifEmpty { lots }).maxByOrNull { it.lotScore }
    }

    private fun buildDriveAndHop(ctx: Ctx, hops: List<Hop>, anchorHop: Hop?): Course? {
        val req = ctx.req
        val slots = template(ctx)
        val aSlot = anchorSlot(slots, anchorHop?.place)
        val chosen = mutableListOf<Hop>()
        if (anchorHop != null) chosen += anchorHop

        var prev = anchorHop?.place?.point ?: req.anchor.point
        slots.forEachIndexed { i, slot ->
            if (i == aSlot) return@forEachIndexed
            val pick = hops
                .filter { it.place.category in slot && chosen.none { c -> c.place.id == it.place.id } }
                .maxByOrNull {
                    0.6 * placeScore(ctx, it.place) + 0.4 * it.lotScore -
                        0.2 * Geo.distanceM(prev, it.place.point) / DH_SEARCH_RADIUS_M -
                        OVERLAP_PENALTY * (ctx.used[it.place.id] ?: 0)
                } ?: return@forEachIndexed
            chosen += pick
            prev = pick.place.point
        }
        if (chosen.size < MIN_STOPS) return null

        // 출발 위치(모르면 앵커)에서 시작하는 총 운전 시간이 가장 짧은 순서
        val start = req.origin ?: req.anchor.point
        val ordered = permutations(chosen).minByOrNull { perm ->
            var t = 0
            var cur = start
            for (h in perm) { t += Geo.estimateDriveMin(cur, h.naviPoint, req.startMinuteOfDay, req.isWeekend); cur = h.naviPoint }
            t
        } ?: chosen

        var clock = req.startMinuteOfDay
        var cur: GeoPoint? = req.origin
        var totalDrive = 0
        var totalWalk = 0
        var driveToStart: Int? = null
        val stops = ordered.mapIndexed { idx, h ->
            val drive = cur?.let { Geo.estimateDriveMin(it, h.naviPoint, clock, req.isWeekend) } ?: 0
            if (idx == 0) driveToStart = if (req.origin != null) drive else null
            totalDrive += drive
            clock += drive + Geo.walkMin(h.walkM)
            totalWalk += h.walkM * 2
            val stop = CourseStop(
                place = h.place, parking = h.lot, parkingStatus = h.lot?.let { ctx.status(it) },
                walkInM = h.walkM, driveInMin = drive, arriveMinuteOfDay = clock,
            )
            clock += h.place.category.stayMin + Geo.walkMin(h.walkM)
            cur = h.naviPoint
            stop
        }
        val farAnchor = anchorHop?.takeIf { it.lot != null && it.walkM > ctx.parkWalkLimit }
        val noLotAnchor = anchorHop?.takeIf { it.lot == null }

        val avgPlace = ordered.map { placeScore(ctx, it.place) }.average()
        val avgLot = ordered.map { it.lotScore }.average()
        val timeFit = 1 - min(1.0, totalDrive / 90.0)
        val score = 0.35 * avgPlace + 0.35 * avgLot + 0.3 * timeFit -
            0.1 * totalWalk / ctx.totalLimit - stops.map { congestionPenalty(it.parkingStatus) }.average()
        val busy = stops.count { it.parkingStatus?.level == CongestionLevel.BUSY || it.parkingStatus?.level == CongestionLevel.FULL }

        return Course(
            id = "dh-" + ordered.joinToString("-") { it.place.id },
            mode = CourseMode.DRIVE_AND_HOP,
            stops = stops,
            mainParking = null,
            mainParkingStatus = null,
            driveToStartMin = driveToStart,
            driveToStartIsLive = false,
            totalWalkM = totalWalk,
            totalDriveMin = totalDrive,
            endMinuteOfDay = clock,
            score = score,
            reasons = commonReasons(ctx) +
                "장소마다 바로 앞 주차 · 총 도보 약 ${totalWalk}m",
            warnings = listOfNotNull(
                if (busy > 0) "혼잡한 주차장이 ${busy}곳 있어요" else null,
                if (ordered.size < slots.size) "주차 가능한 장소가 적어 ${ordered.size}곳으로 줄였어요" else null,
                farAnchor?.let { "${it.place.name}은 주차장에서 조금 걸어야 해요 (도보 ${it.walkM}m)" },
                noLotAnchor?.let { "${it.place.name} 근처 주차장 정보가 없어 목적지로 바로 안내해요" },
            ),
        )
    }

    // ───────────────────────── 이유·경고 ─────────────────────────

    private fun commonReasons(ctx: Ctx): List<String> {
        val w = ctx.req.weather ?: return emptyList()
        return when {
            w.precipitating || (w.rainProbability ?: 0) >= 60 -> listOf("비 예보 → 실내 위주로 구성")
            w.temperatureC != null && w.temperatureC <= 0.0 -> listOf("영하 ${w.temperatureC.roundToInt()}℃ → 실내 위주로 구성")
            w.temperatureC != null && w.temperatureC >= 33.0 -> listOf("${w.temperatureC.roundToInt()}℃ 무더위 → 실내 위주로 구성")
            else -> emptyList()
        }
    }

    private fun parkingReason(lot: ParkingLot, st: ParkingStatus): String {
        if (lot.id == NO_LOT_ID) return "주차장 정보 없음 · 목적지 도착 후 근처 주차"
        val fee = when (lot.feePer30Min) { null -> ""; 0 -> " · 무료"; else -> " · 30분 ${lot.feePer30Min}원" }
        val congestion = if (st.source == OccupancySource.UNKNOWN) "혼잡도 정보 없음"
        else "${st.source.label} 혼잡도 ${st.level.label}"
        return "${lot.name}: $congestion$fee"
    }

    /** 배터리·연료가 적으면 경고하고 가까운 충전소·주유소를 알려 준다 (주행 가능 거리 계산은 하지 않음) */
    private fun energyNotes(req: CourseRequest, all: List<Place>, courses: List<Course>): List<String> {
        val e = req.energy
        if (!e.isLow) return emptyList()
        val kind = if (e.isEv) EV_CHARGER else GAS_STATION
        val near = courses.firstOrNull()?.firstNaviTarget?.point ?: req.anchor.point
        val station = all.filter { it.category == kind }.minByOrNull { Geo.distanceM(it.point, near) }
        val what = if (e.isEv) "배터리" else "연료"
        val tail = station?.let { " · 근처 ${kind.label}: ${it.name} (${(Geo.distanceM(it.point, near) / 1000).let { d -> "%.1f".format(d) }}km)" } ?: ""
        return listOf("$what ${e.percent}% 남음$tail")
    }
}

/** 작은 리스트(≤ 5)의 모든 순열 */
internal fun <T> permutations(items: List<T>): List<List<T>> {
    if (items.size <= 1) return listOf(items)
    val out = mutableListOf<List<T>>()
    for (i in items.indices) {
        val rest = items.toMutableList().also { it.removeAt(i) }
        for (p in permutations(rest)) out += listOf(items[i]) + p
    }
    return out
}

/** 실시간 교통으로 받은 "첫 목적지까지 운전 시간"을 코스에 반영한다 */
fun Course.withLiveDriveToStart(liveMin: Int): Course {
    val old = driveToStartMin ?: 0
    val delta = liveMin - old
    return copy(
        driveToStartMin = liveMin,
        driveToStartIsLive = true,
        totalDriveMin = totalDriveMin + delta,
        endMinuteOfDay = endMinuteOfDay + delta,
        stops = stops.map { it.copy(arriveMinuteOfDay = it.arriveMinuteOfDay + delta) },
        reasons = reasons + "실시간 교통 반영 · 첫 도착 ${stops.first().arriveMinuteOfDay.plus(delta).toClock()}",
    )
}
