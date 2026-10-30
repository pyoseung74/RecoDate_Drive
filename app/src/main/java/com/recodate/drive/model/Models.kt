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
 * 방문객이 쓸 수 없는 주차장.
 * - 이름에 "전용"이 들어가면 모두 제외 (투숙객·고객·직원·장애인·경차·전기차·"타워D" 전용 등)
 * - 거주·관계자용: 거주자 우선, 주민 우선, 입주자·입주민, 아파트·빌라·오피스텔·맨션·기숙사·사택,
 *   관계자·직원·교직원·임직원·사원, 등록차량·정기권, 사유지
 * "주민센터"·"행정복지센터"처럼 방문객이 쓰는 공공 주차장은 막지 않는다.
 */
fun isResidentOnlyParking(name: String): Boolean {
    val n = name.replace(" ", "")
    val words = listOf(
        "전용", "거주자", "주민우선", "입주자", "입주민", "아파트", "빌라", "오피스텔", "맨션", "기숙사", "사택",
        "관계자", "직원", "교직원", "임직원", "사원", "등록차량", "정기권", "사유지",
    )
    return words.any { it in n }
}

fun Int.toClock(): String {
    val m = ((this % 1440) + 1440) % 1440
    return "%02d:%02d".format(m / 60, m % 60)
}
