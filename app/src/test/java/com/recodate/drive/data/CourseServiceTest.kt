package com.recodate.drive.data

import com.recodate.drive.model.Anchor
import com.recodate.drive.model.GeoPoint
import com.recodate.drive.model.Place
import com.recodate.drive.model.PlaceCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseServiceTest {

    private val service = CourseService(DemoPlaceSource(), DemoParkingSource(), NoRealtimeParking(), NoTraffic(), NoWeather())
    private val here = GeoPoint(37.7725, 128.9460)
    private val cafeNextDoor = Place("c1", "로끼맨션", PlaceCategory.CAFE, GeoPoint(37.7727, 128.9462))
    private val sameNamePlace = Place("p1", "안목해변 커피거리", PlaceCategory.CAFE, GeoPoint(37.7726, 128.9461))

    @Test
    fun parkingDestination_isOnlyACenterPoint() {
        val a = service.withAnchorPlace(Anchor("안목해변 공영주차장", here), listOf(cafeNextDoor))
        assertNull("주차장 목적지는 꼭 들를 곳이 아니다", a.place)
    }

    @Test
    fun nearbyShopWithDifferentName_isNotMistakenForTheDestination() {
        val a = service.withAnchorPlace(Anchor("안목해변 커피거리", here), listOf(cafeNextDoor))
        assertEquals("anchor", a.place?.id) // 근처 다른 가게가 아니라 목적지 자체를 넣는다
        assertEquals("안목해변 커피거리", a.place?.name)
    }

    @Test
    fun sameNamedPlaceNearby_isMatched() {
        val a = service.withAnchorPlace(Anchor("안목해변커피거리", here), listOf(cafeNextDoor, sameNamePlace))
        assertEquals("p1", a.place?.id)
    }

    @Test
    fun partialNameMatch_isNotTheDestination() {
        val pizza = Place("pz", "피자스탠드 스타필드하남", PlaceCategory.FOOD, GeoPoint(37.5448, 127.2259))
        val a = service.withAnchorPlace(Anchor("스타필드 하남", GeoPoint(37.5448, 127.2259)), listOf(pizza))
        assertEquals("anchor", a.place?.id)
        assertEquals("강남역", normalizeName("강남역[수도권2호선]"))
    }

    @Test
    fun residentOnlyParking_isDetected() {
        val blocked = listOf(
            "대명아파트이면도로거주자우선주차장", "쏠비치 삼척 타워D전용주차장", "101동전용 주차장",
            "입주민 주차장", "래미안아파트 주차장", "교직원 주차장", "임직원주차장", "직원 주차장",
            "등록차량 외 주차금지", "전기차 전용 충전주차", "장애인전용주차구역", "OO대학교 기숙사 주차장",
        )
        val allowed = listOf(
            "견소동공영주차장", "안목해변 주차장", "OO동 행정복지센터 주차장", "주민센터 주차장", "쏠비치 삼척 리조트주차장",
            "대명아파트앞 공영주차장", "스타필드 하남 고객전용 주차장", "호텔 투숙객 전용 주차장", "빌라드아모레 호텔 주차장",
            "로끼맨션 주차장", "OO오피스텔 공용주차장", "해변 노상주차장", "OO마트 방문객 전용",
        )
        blocked.forEach { assertTrue("막아야 함: $it", com.recodate.drive.model.isResidentOnlyParking(it)) }
        allowed.forEach { assertTrue("허용해야 함: $it", !com.recodate.drive.model.isResidentOnlyParking(it)) }
    }

    @Test
    fun parkingNames_areDetected() {
        assertTrue(isParkingName("환희컵박물관 주차장"))
        assertTrue(isParkingName("견소동공영주차장"))
        assertTrue(!isParkingName("안목해맞이공원"))
    }
}
