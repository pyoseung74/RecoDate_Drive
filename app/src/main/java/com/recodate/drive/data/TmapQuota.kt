package com.recodate.drive.data

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId

/**
 * TMAP 호출 한도 가드. 원칙: TMAP은 절대 무료 한도를 넘겨 쓰지 않는다.
 *
 * 공식 사이트에서 확인한 문구는 "POI 검색 일 2만 건, 경로탐색 일 1천 건 무료"뿐이고
 * API별 정확한 한도·월 한도·초과 시 과금 여부는 확인되지 않았다. 그래서 두 겹으로 막는다.
 * - 하루 상한: 공개 무료 수치의 10% (POI 2,000 / 경로 100)
 * - 한 달 상한: 공개 "하루" 수치 그대로 (POI 20,000 / 경로 1,000)
 *   → 무료 한도가 실제로는 "월" 단위였더라도 넘지 않는다.
 * local.properties로 더 낮출 수는 있지만, 공개 수치의 80%보다 높게는 못 올린다.
 *
 * 호출 "전에" 1건을 차감한다. 실패한 호출도 한도에 포함된다(서버가 셀 수 있으므로).
 * 카운터는 이 기기에만 있다. 같은 키를 여러 기기에서 쓰면 TMAP 대시보드의 사용 제한량 설정이 필요하다.
 */
class TmapQuota(
    private val store: QuotaStore,
    dailyOverride: Map<TmapApi, Int> = emptyMap(),
    private val today: () -> LocalDate = { LocalDate.now(ZoneId.of("Asia/Seoul")) },
) {
    private val dailyLimit: Map<TmapApi, Int> = TmapApi.entries.associateWith { api ->
        (dailyOverride[api] ?: api.defaultDailyLimit).coerceIn(0, api.hardDailyCeiling)
    }

    /** 1건을 쓸 수 있으면 차감하고 true. 하루·한 달 상한 중 하나라도 차면 false (호출하면 안 됨) */
    @Synchronized
    fun tryAcquire(api: TmapApi): Boolean {
        // 날짜는 한 번만 읽는다 (자정·월말에 일·월 키가 서로 다른 날짜를 가리키지 않게)
        val date = today()
        val d = dayKey(api, date)
        val m = monthKey(api, date)
        val usedDay = store.get(d)
        val usedMonth = store.get(m)
        if (usedDay >= dailyLimit(api) || usedMonth >= monthlyLimit(api)) return false
        // 하루·한 달 카운터를 한 번에 저장한다. 저장이 확인되지 않으면 호출하지 않는다(덜 세는 일이 없도록).
        return store.putAll(mapOf(d to usedDay + 1, m to usedMonth + 1))
    }

    fun usedToday(api: TmapApi) = store.get(dayKey(api))
    fun usedThisMonth(api: TmapApi) = store.get(monthKey(api))
    fun dailyLimit(api: TmapApi) = dailyLimit.getValue(api)
    fun monthlyLimit(api: TmapApi) = api.monthlyLimit
    fun exhausted(api: TmapApi) =
        usedToday(api) >= dailyLimit(api) || usedThisMonth(api) >= monthlyLimit(api)

    fun summary(): String = TmapApi.entries.joinToString(" · ") { api ->
        "${api.label} 오늘 ${usedToday(api)}/${dailyLimit(api)}, 이번 달 ${usedThisMonth(api)}/${monthlyLimit(api)}"
    }

    private fun dayKey(api: TmapApi, date: LocalDate = today()) = "d:$date:${api.name}"
    private fun monthKey(api: TmapApi, date: LocalDate = today()) = "m:${date.toString().take(7)}:${api.name}"
}

enum class TmapApi(val label: String, private val publishedFreePerDay: Int) {
    POI("POI 검색", 20_000),
    ROUTE("경로", 1_000);

    val defaultDailyLimit: Int get() = publishedFreePerDay / 10
    val hardDailyCeiling: Int get() = publishedFreePerDay * 8 / 10
    val monthlyLimit: Int get() = publishedFreePerDay
}

/** TMAP 한도에 걸려 호출하지 않았음 */
class TmapQuotaExceeded(api: TmapApi) : RuntimeException("TMAP ${api.label} 한도 도달")

interface QuotaStore {
    fun get(key: String): Int
    /** 여러 값을 한 번에(원자적으로) 저장한다. 디스크에 기록됐으면 true */
    fun putAll(values: Map<String, Int>): Boolean
}

/**
 * 앱을 껐다 켜도 유지되는 저장소.
 * 한 번의 commit()으로 두 값을 함께 기록한다(SharedPreferences는 파일을 통째로 바꿔 써서
 * 중간에 꺼져도 두 값이 같이 남거나 같이 안 남는다). commit()이 실패하면 false.
 * 카운터는 절대 지우지 않는다. 기기 시계가 뒤로 가거나 시간대가 바뀌어도
 * 지금 쓰는 기간의 카운터가 지워지는 일이 없게 하기 위해서다. (하루 4개 키, 1년 약 1,500개 정도로 작다)
 */
class PrefsQuotaStore(context: Context) : QuotaStore {
    private val prefs = context.getSharedPreferences("tmap_quota", Context.MODE_PRIVATE)
    override fun get(key: String) = prefs.getInt(key, 0)

    override fun putAll(values: Map<String, Int>): Boolean = runCatching {
        val editor = prefs.edit()
        values.forEach { (k, v) -> editor.putInt(k, v) }
        editor.commit()
    }.getOrDefault(false)
}

class MemoryQuotaStore : QuotaStore {
    private val map = mutableMapOf<String, Int>()
    var failWrites = false
    override fun get(key: String) = map[key] ?: 0
    override fun putAll(values: Map<String, Int>): Boolean {
        if (failWrites) return false
        map.putAll(values); return true
    }
}
