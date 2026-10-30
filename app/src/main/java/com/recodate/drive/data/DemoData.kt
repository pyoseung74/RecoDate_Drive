package com.recodate.drive.data

import com.recodate.drive.model.Anchor
import com.recodate.drive.model.DataSource
import com.recodate.drive.model.GeoPoint
import com.recodate.drive.model.ParkingLot
import com.recodate.drive.model.Place
import com.recodate.drive.model.PlaceCategory
import com.recodate.drive.model.PlaceCategory.ATTRACTION
import com.recodate.drive.model.PlaceCategory.CAFE
import com.recodate.drive.model.PlaceCategory.EV_CHARGER
import com.recodate.drive.model.PlaceCategory.FOOD
import com.recodate.drive.model.PlaceCategory.GAS_STATION
import com.recodate.drive.model.PlaceCategory.INDOOR_ACTIVITY
import com.recodate.drive.model.PlaceCategory.WALK

/**
 * API 키가 없거나 네트워크가 안 될 때 쓰는 강릉 데모 데이터.
 * 장소는 RecoDate 강릉 데이터에서 가져왔다. 지명은 실제 지역을 참고했고 좌표는 대략적이다.
 * 주차장·충전소의 이름과 면수·요금은 시연용 예시다. 화면에는 "데모 데이터"로 표시한다.
 */
object DemoData {

    val places: List<Place> = listOf(
        p("br01", "초당 브런치 테이블", CAFE, 37.7912, 128.9142, 0.6),
        p("br02", "강문 오션뷰 브런치", CAFE, 37.7968, 128.9182, 0.7),
        p("br03", "교동 베이커리 하우스", CAFE, 37.7655, 128.9030, 0.5),
        p("br04", "안목 샌드위치 가게", FOOD, 37.7728, 128.9468, 0.5),
        p("ml01", "초당 순두부 거리", FOOD, 37.7901, 128.9155, 0.9),
        p("ml02", "감자옹심이 노포", FOOD, 37.7612, 128.9002, 0.7),
        p("ml03", "경포 물회 식당", FOOD, 37.8040, 128.9068, 0.6),
        p("ml04", "주문진 수산시장 회센터", FOOD, 37.8935, 128.8295, 0.8),
        p("ml05", "사천진 조개구이", FOOD, 37.8425, 128.8745, 0.6),
        p("ml06", "강문 파스타 키친", FOOD, 37.7958, 128.9198, 0.5),
        p("ml07", "경포 오션 다이닝", FOOD, 37.8030, 128.9090, 0.6),
        p("ml08", "중앙시장 먹거리 골목", FOOD, 37.7547, 128.8993, 0.9),
        p("ml09", "안목 수제버거", FOOD, 37.7718, 128.9480, 0.6),
        p("cf01", "안목해변 커피거리", CAFE, 37.7720, 128.9475, 0.9),
        p("cf02", "테라로사 커피공장", CAFE, 37.7203, 128.8958, 0.9),
        p("cf03", "강릉 감성 로스터리", CAFE, 37.7570, 128.8960, 0.5),
        p("cf04", "사천진 바다뷰 카페", CAFE, 37.8432, 128.8738, 0.6),
        p("cf05", "초당 한옥 카페", CAFE, 37.7895, 128.9130, 0.6),
        p("cf06", "강문 루프탑 카페", CAFE, 37.7965, 128.9200, 0.6),
        p("cf07", "주문진 등대 카페", CAFE, 37.8960, 128.8305, 0.5),
        p("ac01", "아르떼뮤지엄 강릉", INDOOR_ACTIVITY, 37.7920, 128.9172, 0.9),
        p("ac02", "경포 아쿠아리움", INDOOR_ACTIVITY, 37.8010, 128.9030, 0.7),
        p("ac03", "오죽헌·시립박물관", ATTRACTION, 37.7790, 128.8785, 0.8),
        p("ac04", "하슬라아트월드", INDOOR_ACTIVITY, 37.7070, 129.0090, 0.7),
        p("ac05", "경포호 자전거 대여", ATTRACTION, 37.7960, 128.9010, 0.5),
        p("ac06", "강릉 원데이 공방 클래스", INDOOR_ACTIVITY, 37.7640, 128.9040, 0.5),
        p("ac07", "정동진 레일바이크", ATTRACTION, 37.6920, 129.0330, 0.7),
        p("ac08", "선교장 한옥 산책", ATTRACTION, 37.7860, 128.8880, 0.7),
        p("ac09", "강릉 보드게임 카페", INDOOR_ACTIVITY, 37.7565, 128.8985, 0.4),
        p("wk01", "경포호 둘레길", WALK, 37.7955, 128.8975, 0.8),
        p("wk02", "송정 해송숲길", WALK, 37.7745, 128.9365, 0.6),
        p("wk03", "강문 솟대다리", WALK, 37.7975, 128.9195, 0.6),
        p("wk04", "월화거리 산책", WALK, 37.7558, 128.8985, 0.7),
        p("wk05", "안목해변 모래사장", WALK, 37.7715, 128.9485, 0.8),
        p("wk06", "사천진 해변 산책", WALK, 37.8440, 128.8750, 0.5),
        p("nt01", "경포해변", ATTRACTION, 37.8055, 128.9085, 0.9),
        p("nt02", "남항진 솔바람다리", ATTRACTION, 37.7695, 128.9535, 0.6),
        p("nt04", "경포대", ATTRACTION, 37.7952, 128.8930, 0.8),
        p("nt05", "주문진 등대", ATTRACTION, 37.8955, 128.8310, 0.5),
        // 충전소·주유소 (에너지 부족 안내용)
        p("ev01", "경포 전기차 충전소", EV_CHARGER, 37.7990, 128.9040, null),
        p("ev02", "강릉역 전기차 충전소", EV_CHARGER, 37.7642, 128.8990, null),
        p("gs01", "교동 주유소", GAS_STATION, 37.7660, 128.8950, null),
    )

    val parkingLots: List<ParkingLot> = listOf(
        lot("pk01", "안목해변 공영주차장", 37.7725, 128.9460, 180, 0),
        lot("pk02", "안목 커피거리 민영주차장", 37.7712, 128.9490, 40, 1500),
        lot("pk03", "경포해변 중앙주차장", 37.8045, 128.9075, 400, 0),
        lot("pk04", "경포호수 광장주차장", 37.7965, 128.8990, 150, 0),
        lot("pk05", "초당 순두부마을 주차장", 37.7905, 128.9140, 120, 0),
        lot("pk06", "아르떼뮤지엄 주차장", 37.7925, 128.9180, 200, 1000),
        lot("pk07", "강문해변 주차장", 37.7970, 128.9190, 90, 0),
        lot("pk08", "중앙시장 공영주차장", 37.7550, 128.8980, 250, 1000),
        lot("pk09", "월화거리 노상주차장", 37.7560, 128.8995, 30, 600),
        lot("pk10", "교동 공영주차장", 37.7645, 128.9020, 80, 500),
        lot("pk11", "오죽헌 주차장", 37.7795, 128.8790, 300, 0),
        lot("pk12", "선교장 주차장", 37.7858, 128.8890, 100, 0),
        lot("pk13", "테라로사 주차장", 37.7208, 128.8950, 150, 0),
        lot("pk14", "사천진해변 주차장", 37.8430, 128.8748, 100, 0),
        lot("pk15", "주문진 수산시장 주차장", 37.8940, 128.8300, 200, 1000),
        lot("pk16", "경포 아쿠아리움 주차장", 37.8015, 128.9035, 120, 1000),
        lot("pk17", "송정해변 주차장", 37.7750, 128.9360, 80, 0),
        lot("pk18", "남항진 주차장", 37.7700, 128.9530, 60, 0),
        lot("pk19", "하슬라아트월드 주차장", 37.7075, 129.0085, 150, 0),
        lot("pk20", "정동진역 공영주차장", 37.6915, 129.0335, 200, 1000),
    )

    /** 출발 화면의 "최근 목적지" 데모 (내비 연동이 안 될 때) */
    val recentAnchors: List<Anchor> = listOf(
        Anchor("안목해변 커피거리", GeoPoint(37.7720, 128.9475), places.first { it.id == "cf01" }),
        Anchor("경포해변", GeoPoint(37.8055, 128.9085), places.first { it.id == "nt01" }),
        Anchor("아르떼뮤지엄 강릉", GeoPoint(37.7920, 128.9172), places.first { it.id == "ac01" }),
        Anchor("강릉 중앙시장", GeoPoint(37.7547, 128.8993), places.first { it.id == "ml08" }),
    )

    /** 데모 모드의 현재 차 위치: 강릉역 */
    val demoCarLocation = GeoPoint(37.7637, 128.8995)

    private fun p(id: String, name: String, cat: PlaceCategory, lat: Double, lon: Double, pop: Double?) =
        Place(id, name, cat, GeoPoint(lat, lon), popularity = pop, address = "강원 강릉시", source = DataSource.DEMO)

    private fun lot(id: String, name: String, lat: Double, lon: Double, cap: Int, fee: Int) =
        ParkingLot(id, name, GeoPoint(lat, lon), capacity = cap, feePer30Min = fee, isPublic = fee == 0, source = DataSource.DEMO)
}
