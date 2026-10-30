package com.recodate.drive.model

/** 위도·경도 좌표 */
data class GeoPoint(val lat: Double, val lon: Double)

/** 장소 종류. stayMin = 기본 머무는 시간(분) */
enum class PlaceCategory(val label: String, val stayMin: Int, val indoorByDefault: Boolean) {
    FOOD("식사", 70, true),
    CAFE("카페", 60, true),
    WALK("산책", 40, false),
    ATTRACTION("관광", 50, false),
    INDOOR_ACTIVITY("실내 놀거리", 90, true),
    EV_CHARGER("충전소", 30, false),
    GAS_STATION("주유소", 10, false),
}

/** 출발 화면의 한 번 탭 선택 칩 */
enum class Interest(val label: String, val categories: Set<PlaceCategory>) {
    MEAL("식사", setOf(PlaceCategory.FOOD)),
    CAFE("카페", setOf(PlaceCategory.CAFE)),
    WALK("산책", setOf(PlaceCategory.WALK, PlaceCategory.ATTRACTION)),
    INDOOR("실내", setOf(PlaceCategory.INDOOR_ACTIVITY, PlaceCategory.CAFE)),
    ACTIVITY("놀거리", setOf(PlaceCategory.INDOOR_ACTIVITY, PlaceCategory.ATTRACTION)),
}

data class Place(
    val id: String,
    val name: String,
    val category: PlaceCategory,
    val point: GeoPoint,
    val indoor: Boolean = category.indoorByDefault,
    val address: String = "",
    /** 0~1 인기도. 모르면 null */
    val popularity: Double? = null,
    val source: DataSource = DataSource.DEMO,
)

/** 주차 혼잡도를 어디서 알았는지 */
enum class OccupancySource(val label: String) {
    REALTIME("실시간"),
    PREDICTED("예상"),
    UNKNOWN("정보 없음"),
}

data class ParkingLot(
    val id: String,
    val name: String,
    val point: GeoPoint,
    /** 총 주차면 수. 모르면 null */
    val capacity: Int? = null,
    /** 30분 요금(원). 무료 = 0, 모르면 null */
    val feePer30Min: Int? = null,
    val isPublic: Boolean? = null,
    val source: DataSource = DataSource.DEMO,
)

/** 특정 시점의 주차장 혼잡도. occupancy = 0(빈) ~ 1(만차) */
data class ParkingStatus(
    val occupancy: Double?,
    val source: OccupancySource,
    val availableSpaces: Int? = null,
) {
    val level: CongestionLevel
        get() = when {
            occupancy == null -> CongestionLevel.UNKNOWN
            occupancy >= 0.9 -> CongestionLevel.FULL
            occupancy >= 0.7 -> CongestionLevel.BUSY
            occupancy >= 0.4 -> CongestionLevel.NORMAL
            else -> CongestionLevel.FREE
        }
}

enum class CongestionLevel(val label: String) {
    FREE("여유"), NORMAL("보통"), BUSY("혼잡"), FULL("매우 혼잡"), UNKNOWN("정보 없음")
}

enum class DataSource { LIVE, DEMO }

/** 방문 예상 시각의 날씨 */
data class Weather(
    val precipitating: Boolean,
    /** 강수확률 0~100 */
    val rainProbability: Int?,
    val temperatureC: Double?,
    val source: DataSource,
) {
    val isHarsh: Boolean
        get() = precipitating || (rainProbability ?: 0) >= 60 ||
            (temperatureC != null && (temperatureC <= 0.0 || temperatureC >= 33.0))
}

/** 차량 에너지 상태 (Vehicle SDK). 퍼센트, 모르면 null */
data class VehicleEnergy(val evSocPercent: Int?, val fuelPercent: Int?) {
    val isEv: Boolean get() = evSocPercent != null
    val percent: Int? get() = evSocPercent ?: fuelPercent
    val isLow: Boolean get() = (percent ?: 100) <= LOW_ENERGY_PERCENT

    companion object {
        const val LOW_ENERGY_PERCENT = 15
        val UNKNOWN = VehicleEnergy(null, null)
    }
}

enum class CourseMode(val label: String, val shortDesc: String) {
    PARK_AND_WALK("한 번 주차", "주차 1번, 걸어서 둘러보기"),
    DRIVE_AND_HOP("차로 이동", "장소마다 차로 이동, 도보 최소화"),
}

/**
 * 코스의 기준점.
 * place 가 있으면(내비 목적지·최근 목적지 등) 그 장소를 코스에 꼭 넣는다.
 * place 가 없으면("내 주변") 검색 중심점으로만 쓴다.
 */
data class Anchor(
    val name: String,
    val point: GeoPoint,
    val place: Place? = null,
)

data class CourseRequest(
    val anchor: Anchor,
    val mode: CourseMode,
    val interests: Set<Interest>,
    /** 자정 기준 분 (예: 14:30 → 870) */
    val startMinuteOfDay: Int,
    val isWeekend: Boolean,
    val weather: Weather?,
    val energy: VehicleEnergy,
    /** 지금 차 위치. 모르면 null */
    val origin: GeoPoint?,
    val courseCount: Int = 3,
)

data class CourseStop(
    val place: Place,
    /** 차로 이동형에서 이 장소에 쓰는 주차장 */
    val parking: ParkingLot? = null,
    val parkingStatus: ParkingStatus? = null,
    /** 앞 지점(주차장 또는 앞 장소)에서 걸어서 오는 거리(m) */
    val walkInM: Int,
    /** 앞 장소에서 차로 오는 시간(분). 도보형이면 0 */
    val driveInMin: Int = 0,
    val arriveMinuteOfDay: Int,
)

data class Course(
    val id: String,
    val mode: CourseMode,
    val stops: List<CourseStop>,
    /** 한 번 주차형의 주차장 */
    val mainParking: ParkingLot?,
    val mainParkingStatus: ParkingStatus?,
    /** 지금 위치 → 첫 주차장 운전 시간(분) */
    val driveToStartMin: Int?,
    val driveToStartIsLive: Boolean,
    val totalWalkM: Int,
    val totalDriveMin: Int,
    val endMinuteOfDay: Int,
    val score: Double,
    /** 카드에 보여 줄 "왜 이 코스인지" 한 줄 이유들 */
    val reasons: List<String>,
    val warnings: List<String>,
    /** 조건에 맞는 코스가 없어 도보 거리 제한을 늘려 만든 코스인지 */
    val relaxedLimits: Boolean = false,
) {
    /** 내비에 처음 넣을 목적지 (주차장 우선) */
    val firstNaviTarget: NaviTarget
        get() {
            val lot = mainParking ?: stops.first().parking
            // "no-lot": 주차장 정보가 없어 목적지 자체로 안내하는 경우
            return if (lot != null) NaviTarget(lot.name, lot.point, isParking = lot.id != "no-lot")
            else NaviTarget(stops.first().place.name, stops.first().place.point, isParking = false)
        }
}

data class NaviTarget(val name: String, val point: GeoPoint, val isParking: Boolean)

/**
 * 방문객이 쓸 수 없는 주차장인지 이름으로 판단한다. 이름만으로는 확실하지 않으므로
 * "확실히 못 쓰는 경우"만 막고, 애매하면 허용한다.
 * 1) 공영·공용·노상 주차장은 항상 허용 ("○○아파트앞 공영주차장"도 누구나 쓸 수 있다)
 * 2) 막는 경우: 거주자 우선, 입주자·입주민, 주민 전용·우선, 관계자, 직원·교직원·임직원,
 *    등록차량·정기권, 사유지, 아파트 단지, 기숙사·사택,
 *    차종·대상 지정 칸(장애인·경차·전기차·임산부 전용), 특정 동 전용("타워D전용", "101동전용")
 * 3) 허용: 고객·방문객·이용객·투숙객 전용 (방문객이 바로 그 고객이다), 주민센터·행정복지센터,
 *    호텔 이름 속 "빌라"처럼 우연히 들어간 단어
 */
fun isResidentOnlyParking(name: String): Boolean {
    val n = name.replace(" ", "")
    if (listOf("공영", "공용", "노상").any { it in n }) return false
    val blockedWords = listOf(
        "거주자", "입주자", "입주민", "주민전용", "주민우선", "관계자", "직원", "등록차량", "정기권", "사유지",
        "아파트", "기숙사", "사택", "장애인전용", "경차전용", "전기차전용", "임산부전용",
    )
    if (blockedWords.any { it in n }) return true
    // 특정 동·타워 전용: "타워D전용", "101동전용", "A동전용"
    return Regex("(타워[A-Za-z0-9]*|[A-Za-z0-9]+동|[A-Za-z]동?)전용").containsMatchIn(n)
}

fun Int.toClock(): String {
    val m = ((this % 1440) + 1440) % 1440
    return "%02d:%02d".format(m / 60, m % 60)
}
