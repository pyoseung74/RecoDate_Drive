package com.recodate.drive.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TmapQuotaTest {

    private var day = LocalDate.of(2026, 10, 30)
    private fun quota(store: QuotaStore = MemoryQuotaStore(), override: Map<TmapApi, Int> = emptyMap()) =
        TmapQuota(store, override) { day }

    @Test
    fun defaults_areTenPercentOfPublishedFreeDaily() {
        val q = quota()
        assertEquals(2_000, q.dailyLimit(TmapApi.POI))
        assertEquals(100, q.dailyLimit(TmapApi.ROUTE))
        assertEquals(20_000, q.monthlyLimit(TmapApi.POI))
        assertEquals(1_000, q.monthlyLimit(TmapApi.ROUTE))
    }

    @Test
    fun blocksExactlyAtDailyLimit() {
        val q = quota()
        repeat(100) { assertTrue(q.tryAcquire(TmapApi.ROUTE)) }
        assertFalse(q.tryAcquire(TmapApi.ROUTE))
        assertEquals(100, q.usedToday(TmapApi.ROUTE)) // 막힌 시도는 세지 않는다
        assertTrue(q.exhausted(TmapApi.ROUTE))
        assertTrue(q.tryAcquire(TmapApi.POI)) // 다른 API는 따로 센다
    }

    @Test
    fun resetsNextDay_butMonthlyLimitStillHolds() {
        val store = MemoryQuotaStore()
        // 10일 동안 매일 하루 상한(100)까지 → 한 달 1,000건 도달
        for (d in 1..10) {
            day = LocalDate.of(2026, 10, d)
            val q = quota(store)
            repeat(100) { assertTrue("day $d", q.tryAcquire(TmapApi.ROUTE)) }
        }
        day = LocalDate.of(2026, 10, 11)
        assertFalse("월 상한 1,000건", quota(store).tryAcquire(TmapApi.ROUTE))
        day = LocalDate.of(2026, 11, 1)
        assertTrue("다음 달은 다시 가능", quota(store).tryAcquire(TmapApi.ROUTE))
    }

    @Test
    fun storageFailure_blocksTheCall() {
        val store = MemoryQuotaStore().apply { failWrites = true }
        val q = quota(store)
        assertFalse("저장 실패면 호출하면 안 됨", q.tryAcquire(TmapApi.POI))
        assertEquals(0, q.usedToday(TmapApi.POI))
    }

    @Test
    fun dayAndMonthAreWrittenTogether() {
        val q = quota()
        repeat(3) { q.tryAcquire(TmapApi.POI) }
        assertEquals(q.usedToday(TmapApi.POI), q.usedThisMonth(TmapApi.POI))
    }

    @Test
    fun clockGoingBackAndForth_keepsEachPeriodsCount() {
        val store = MemoryQuotaStore()
        day = LocalDate.of(2026, 10, 30)
        repeat(100) { quota(store).tryAcquire(TmapApi.ROUTE) }
        day = LocalDate.of(2026, 10, 29) // 시계가 하루 뒤로 감
        repeat(5) { quota(store).tryAcquire(TmapApi.ROUTE) }
        day = LocalDate.of(2026, 10, 30) // 다시 원래 날짜
        val q = quota(store)
        assertEquals(100, q.usedToday(TmapApi.ROUTE))
        assertFalse("원래 날짜의 하루 상한은 그대로", q.tryAcquire(TmapApi.ROUTE))
        assertEquals(105, q.usedThisMonth(TmapApi.ROUTE))
    }

    @Test
    fun overrideCanLowerButNotExceedHardCeiling() {
        assertEquals(10, quota(override = mapOf(TmapApi.ROUTE to 10)).dailyLimit(TmapApi.ROUTE))
        assertEquals(800, quota(override = mapOf(TmapApi.ROUTE to 999_999)).dailyLimit(TmapApi.ROUTE))
        assertEquals(0, quota(override = mapOf(TmapApi.POI to 0)).dailyLimit(TmapApi.POI))
        assertFalse(quota(override = mapOf(TmapApi.POI to 0)).tryAcquire(TmapApi.POI))
    }
}
