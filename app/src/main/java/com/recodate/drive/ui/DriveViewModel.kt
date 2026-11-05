package com.recodate.drive.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recodate.drive.AppContainer
import com.recodate.drive.BuildConfig
import com.recodate.drive.data.MY_AREA
import com.recodate.drive.data.cancellableAttempt
import com.recodate.drive.vehicle.effectiveVehicleState
import com.recodate.drive.data.TmapApi
import com.recodate.drive.engine.Geo
import com.recodate.drive.data.Recommendation
import com.recodate.drive.model.Anchor
import com.recodate.drive.model.Course
import com.recodate.drive.model.CourseMode
import com.recodate.drive.model.GeoPoint
import com.recodate.drive.model.Interest
import com.recodate.drive.model.NaviTarget
import com.recodate.drive.model.VehicleEnergy
import com.recodate.drive.model.Weather
import com.recodate.drive.navi.NaviEvent
import com.recodate.drive.navi.NaviGateway
import com.recodate.drive.vehicle.DriveState
import com.recodate.drive.vehicle.VehicleState
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Screen { HOME, RESULTS, RUN }

/** 실행 화면의 단계 */
/** 내비 경로 요청 상태. 내비가 RouteStarted를 알려 줘야 GUIDING이 된다 */
enum class RouteStatus { REQUESTING, GUIDING, FAILED }

sealed interface RunPhase {
    /** 주차장(또는 장소)으로 가는 단계 */
    data class DrivingTo(
        val stopIndex: Int,
        val target: NaviTarget,
        val route: RouteStatus = RouteStatus.REQUESTING,
        val failReason: String? = null,
    ) : RunPhase
    /** 주차 완료. stopIndex 장소부터 걸어서 돌기 (한 번 주차형) 또는 해당 장소 방문 (차로 이동형) */
    data class Visiting(
        val stopIndex: Int,
        /** P단 + 위치로 자동 도착 처리했는지 (틀렸으면 되돌릴 수 있게) */
        val autoArrived: Boolean = false,
        /** 자동 도착 전의 안내 단계 (되돌리기용) */
        val from: DrivingTo? = null,
    ) : RunPhase
    data object Finished : RunPhase
}

data class UiState(
    val screen: Screen = Screen.HOME,
    val anchors: List<Anchor> = emptyList(),
    val naviDestination: Anchor? = null,
    val selectedAnchor: Anchor? = null,
    val mode: CourseMode = CourseMode.PARK_AND_WALK,
    /** 사용자가 직접 모드를 골랐는지 (안 골랐으면 날씨로 자동 제안) */
    val modeChosenByUser: Boolean = false,
    val interests: Set<Interest> = emptySet(),
    val loading: Boolean = false,
    val error: String? = null,
    val recommendation: Recommendation? = null,
    val variation: Int = 0,
    val activeCourse: Course? = null,
    val phase: RunPhase? = null,
    val toast: String? = null,
    /** 실행 중 지연(분): 마지막으로 주차장에 도착한 시각 − 계획 시각. 음수면 일찍 도착 */
    val delayMin: Int? = null,
    /** 충전 제안을 골랐는지: 그 단계에서 주차장 대신 충전소로 안내한다 */
    val chargeChosen: Boolean = false,
)

class DriveViewModel(
    private val c: AppContainer,
    /** 지금 한국 시각(자정부터 분) */
    private val nowMinute: () -> Int = {
        java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul")).let { it.hour * 60 + it.minute }
    },
) : ViewModel() {

    companion object {
        const val ROUTE_TIMEOUT_MS = 25_000L
        /** 내비 목적지와 요청 지점이 이 거리 안이면 같은 목적지로 본다 (내비가 입구 좌표로 보정할 수 있음) */
        const val ROUTE_MATCH_M = 100.0
        /** P단으로 세운 곳이 목표 주차장에서 이 거리 안이면 도착으로 본다 */
        const val AUTO_ARRIVE_M = 300.0
        /** P단을 이만큼 유지해야 자동 도착 (잠깐 P에 넣었다 빼는 경우 제외) */
        const val AUTO_ARRIVE_HOLD_MS = 5_000L
        /** 내비 안내가 꺼진 뒤 이만큼 기다렸다가 확인 (도착 이벤트가 먼저 오면 그것으로 처리) */
        const val GUIDE_END_GRACE_MS = 4_000L
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    /** 실제 차량 값이 들어오면 그것을, 아니면 데모 값을 쓴다. 시연 스위치(배터리 낮음)도 반영 */
    val vehicle: StateFlow<VehicleState> =
        combine(c.vehicle.state, c.demoVehicle.state, c.demo.lowEnergy) { real, demo, low ->
            val base = effectiveVehicleState(c.isAutomotive, real, demo)
            if (low) base.copy(energy = VehicleEnergy(evSocPercent = 12, fuelPercent = null)) else base
        }.stateIn(viewModelScope, SharingStarted.Eagerly, VehicleState())

    val rainDemo: StateFlow<Boolean> = c.demo.rain
    val lowEnergyDemo: StateFlow<Boolean> = c.demo.lowEnergy
    val naviConnected: StateFlow<Boolean> = c.navi.connected
    val isAutomotive: Boolean get() = c.isAutomotive
    val usingLiveKeys: Boolean get() = c.hasTmapKey || c.hasDataKey

    /** 시연 패널에 보여 줄 TMAP 사용량 (키가 없으면 null) */
    fun tmapUsage(): String? = if (c.hasTmapKey) c.tmapQuota.summary() else null

    /** TMAP 한도에 걸려 일부를 예상값·데모로 대신했는지 */
    fun tmapLimited(): Boolean = c.hasTmapKey && TmapApi.entries.any { c.tmapQuota.exhausted(it) }

    private val navi: NaviGateway get() = if (c.isAutomotive || c.navi.connected.value) c.navi else c.demoNavi
    private var recommendJob: Job? = null
    private var routeTimeoutJob: Job? = null
    /** 경로 요청 번호와 보낸 시각. 이전 요청에 대한 콜백·확인 결과를 걸러 낸다 */
    private var requestSeq = 0
    private var requestSentAt = 0L
    private var routeGateway: NaviGateway? = null
    private var verifyJob: Job? = null
    /** 마지막으로 확인된 P 또는 전진 기어. 후진·중립·모름은 건너뛴다 (P→R→D도 출발로 본다) */
    private var lastSettled: DriveState = DriveState.UNKNOWN
    /** P단에 들어간 시각(System.nanoTime). P가 아니면 null */
    private var parkedAt: Long? = null
    private var autoArriveJob: Job? = null
    private var guideEndJob: Job? = null
    /** init보다 먼저 선언해야 init의 첫 목록 조회 작업이 여기 남는다 */
    private var anchorsJob: Job? = null

    init {
        runCatching { c.vehicle.start() }
        runCatching { c.navi.start() }
        viewModelScope.launch { merge(c.navi.events, c.demoNavi.events).collect(::onNaviEvent) }
        viewModelScope.launch { vehicle.collect(::onVehicle) }
        loadAnchors()
    }

    override fun onCleared() {
        c.vehicle.stop()
        c.navi.stop()
    }

    // ───────────── 출발 화면 ─────────────

    /**
     * 앱 화면이 다시 보일 때(내비에서 돌아오거나 앱을 다시 열 때) 출발 화면이면 목적지 목록을 새로 읽는다.
     * ViewModel은 프로세스 동안 유지되므로, 그사이 내비에 새로 찍은 목적지를 여기서 반영한다.
     */
    fun onAppVisible() {
        val s = _ui.value
        if (s.screen == Screen.HOME && !s.loading) loadAnchors()
    }

    fun loadAnchors() {
        anchorsJob?.cancel()
        anchorsJob = launchLoadAnchors()
    }

    private fun launchLoadAnchors() = viewModelScope.launch {
        // Pleos 차량이면 NaviHelper 연결을 잠깐 기다린다 (초기화는 비동기)
        if (c.isAutomotive) withTimeoutOrNull(4_000) { c.navi.connected.first { it } }
        val n = navi
        val destJob = async { if (!c.isAutomotive || n.connected.value) n.currentDestination() else null }
        val recentJob = async { n.recentDestinations() }
        val hereJob = async { if (!c.isAutomotive || n.connected.value) n.currentLocation() else null }
        val dest = destJob.await()
        // 실제 내비에 최근 목적지가 없으면 시연용 강릉 목적지를 함께 보여 준다
        val recent = recentJob.await().ifEmpty { c.demoNavi.recentDestinations() }
        val here = (hereJob.await() ?: if (!c.isAutomotive) c.demoNavi.currentLocation() else null)
            ?.let { Anchor(MY_AREA, it) }
        val list = listOfNotNull(dest, here) + recent.filter { it.name != dest?.name }
        _ui.update {
            it.copy(
                naviDestination = dest,
                anchors = list,
                // 내비 목적지가 새로 바뀌었으면 그것을 고른다. 아니면 고른 것을 유지(목록에 남아 있을 때)
                selectedAnchor = when {
                    dest != null && dest != it.naviDestination -> dest
                    it.selectedAnchor != null && it.selectedAnchor in list -> it.selectedAnchor
                    else -> dest ?: recent.firstOrNull() ?: here
                },
            )
        }
    }

    fun selectAnchor(a: Anchor) = _ui.update { it.copy(selectedAnchor = a) }

    fun selectMode(m: CourseMode) = _ui.update { it.copy(mode = m, modeChosenByUser = true) }

    fun toggleInterest(i: Interest) = _ui.update {
        it.copy(interests = if (i in it.interests) it.interests - i else it.interests + i)
    }

    /**
     * 디버그 빌드 전용: adb로 목적지와 모드를 넣어 바로 추천한다 (테스트 녹화용, TMAP 경로 호출 안 함).
     * named=true면 내비 목적지처럼 이름 있는 목적지, false면 "내 주변"처럼 중심점만.
     */
    fun debugRecommend(name: String, lat: Double, lon: Double, mode: CourseMode, named: Boolean) = viewModelScope.launch {
        // 좌표 없이 이름만 주면 TMAP 장소 검색 1건으로 좌표를 찾는다 (한도 가드 적용)
        val (realName, point) = if (lat == 0.0 && lon == 0.0 && c.hasTmapKey) {
            com.recodate.drive.data.tmapSearchOne(BuildConfig.TMAP_APP_KEY, c.tmapQuota, name)
                ?: run { _ui.update { it.copy(error = "테스트 목적지를 찾지 못했어요: $name") }; return@launch }
        } else name to GeoPoint(lat, lon)
        android.util.Log.d("DebugRun", "anchor '$realName' $point mode=$mode named=$named")
        val anchor = Anchor(if (named) realName else MY_AREA, point)
        _ui.update {
            it.copy(screen = Screen.HOME, selectedAnchor = anchor, mode = mode, modeChosenByUser = true,
                interests = emptySet(), activeCourse = null, phase = null)
        }
        recommend(useLiveTraffic = false, label = name)
    }

    /** 디버그: 마지막 추천 결과 요약을 로그로 남긴다 */
    /** 디버그 빌드 전용(시연 녹화): 이름으로 장소를 찾아 Pleos 내비 목적지로 설정한 뒤 출발 화면을 새로 불러온다 */
    fun debugSetNaviDestination(name: String) = viewModelScope.launch {
        val found = com.recodate.drive.data.tmapSearchOne(BuildConfig.TMAP_APP_KEY, c.tmapQuota, name) ?: return@launch
        c.navi.startRoute(NaviTarget(found.first, found.second, isParking = false))
        delay(8_000)
        _ui.update { it.copy(screen = Screen.HOME, selectedAnchor = null, activeCourse = null, phase = null,
            mode = CourseMode.PARK_AND_WALK, modeChosenByUser = false, interests = emptySet()) }
        loadAnchors()
    }

    private fun logResult(rec: com.recodate.drive.data.Recommendation) = runCatching {
        android.util.Log.d("DebugRun", "result '${rec.request.anchor.name}' ${rec.request.mode} courses=${rec.courses.size} " +
            rec.courses.joinToString(" | ") { c ->
                "${c.mainParking?.name ?: "-"}: " + c.stops.joinToString(",") { it.place.name } + " walk=${c.totalWalkM}"
            })
    }

    fun recommend(refresh: Boolean = false, useLiveTraffic: Boolean = true, label: String? = null) {
        val s = _ui.value
        val anchor = s.selectedAnchor ?: return
        recommendJob?.cancel()
        recommendJob = viewModelScope.launch {
            val variation = if (refresh) s.variation + 1 else 0
            _ui.update { it.copy(loading = true, error = null, variation = variation) }
            cancellableAttempt {
                // 차량 위치를 모르면 ETA를 꾸며내지 않는다.
                val origin: GeoPoint? = if (!c.isAutomotive || navi.connected.value) navi.currentLocation() else null
                var rec = c.courseService.recommend(anchor, s.mode, s.interests, origin, vehicle.value.energy, variation,
                    useLiveTraffic = useLiveTraffic, snapshotLabel = label ?: anchor.name)
                // 사용자가 모드를 고르지 않았고 날씨가 나쁘면 "차로 이동"으로 자동 전환
                if (!s.modeChosenByUser && s.mode == CourseMode.PARK_AND_WALK && rec.weather?.isHarsh == true) {
                    rec = c.courseService.recommend(anchor, CourseMode.DRIVE_AND_HOP, s.interests, origin, vehicle.value.energy, variation,
                        useLiveTraffic = useLiveTraffic, snapshotLabel = label ?: anchor.name)
                    _ui.update { it.copy(mode = CourseMode.DRIVE_AND_HOP, toast = "날씨가 좋지 않아 '차로 이동' 코스로 추천했어요") }
                }
                rec
            }.onSuccess { rec ->
                if (BuildConfig.DEBUG) logResult(rec)
                _ui.update {
                    it.copy(
                        loading = false,
                        recommendation = rec,
                        screen = Screen.RESULTS,
                        error = if (rec.courses.isEmpty()) "조건에 맞는 코스를 찾지 못했어요. 모드나 관심사를 바꿔 보세요." else null,
                    )
                }
            }.onFailure { e ->
                _ui.update { it.copy(loading = false, error = "추천 정보를 가져오지 못했어요. 연결을 확인하고 다시 시도해 주세요.") }
            }
        }
    }

    // ───────────── 실행 ─────────────

    fun startCourse(course: Course) {
        recommendJob?.cancel()
        _ui.update { it.copy(activeCourse = course, screen = Screen.RUN, delayMin = null, chargeChosen = false) }
        requestRoute(0, course.firstNaviTarget)
    }

    /**
     * 경로를 요청하고 "요청 중"으로 둔다. 내비 상태로 안내가 확인되면 "안내 중",
     * Error를 보내거나 25초 뒤 마지막 확인에서도 안내 중이 아니면 "실패"가 된다. (에뮬레이터에서 시작까지 약 7초)
     * Pleos 차량에서는 내비가 연결되지 않았을 때 데모 내비로 넘어가지 않는다.
     */
    private fun requestRoute(stopIndex: Int, target: NaviTarget) {
        routeTimeoutJob?.cancel()
        verifyJob?.cancel()
        autoArriveJob?.cancel()
        guideEndJob?.cancel()
        val gateway: NaviGateway? = when {
            c.navi.connected.value -> c.navi
            !c.isAutomotive -> c.demoNavi
            else -> null
        }
        _ui.update { it.copy(phase = RunPhase.DrivingTo(stopIndex, target, RouteStatus.REQUESTING)) }
        requestSeq++
        requestSentAt = System.nanoTime()
        routeGateway = gateway
        val sent = gateway?.startRoute(target) ?: false
        if (!sent) {
            failRoute(if (gateway == null) "차량 내비에 연결되지 않았어요" else "내비에 경로 요청을 보내지 못했어요")
            return
        }
        val seq = requestSeq
        routeTimeoutJob = viewModelScope.launch {
            delay(ROUTE_TIMEOUT_MS)
            val p = _ui.value.phase as? RunPhase.DrivingTo ?: return@launch
            if (seq != requestSeq || p.route != RouteStatus.REQUESTING) return@launch
            // 실패로 바꾸기 전에 내비 상태를 마지막으로 한 번 더 확인한다
            val guiding = isGuidingTo(p.target)
            if (seq != requestSeq || _ui.value.phase != p) return@launch
            if (guiding) markGuiding(seq) else failRoute("내비가 응답하지 않아요")
        }
    }

    private fun failRoute(reason: String) = _ui.update { s ->
        val p = s.phase as? RunPhase.DrivingTo ?: return@update s
        s.copy(phase = p.copy(route = RouteStatus.FAILED, failReason = reason))
    }

    /** 차로 이동형: 다음 장소는 충전소로 안내하도록 미리 고른다 (다시 출발하면 자동 안내에도 적용) */
    fun chooseChargerNext(on: Boolean) = _ui.update { it.copy(chargeChosen = on) }

    /** 충전 제안이 있으면: 지금 가는 주차장 대신 충전소로 안내를 바꾼다 (사용자가 고를 때만) */
    fun routeToCharger() {
        val s = _ui.value
        val p = s.phase as? RunPhase.DrivingTo ?: return
        val ch = s.activeCourse?.charge?.takeIf { it.stopIndex == p.stopIndex } ?: return
        _ui.update { it.copy(chargeChosen = true) }
        if (p.target.point == ch.charger.point) return
        requestRoute(p.stopIndex, NaviTarget(ch.charger.name, ch.charger.point, isParking = true))
    }

    /** 실패한 경로 요청 다시 보내기 */
    fun retryRoute() {
        val p = _ui.value.phase as? RunPhase.DrivingTo ?: return
        requestRoute(p.stopIndex, p.target)
    }

    /** 차로 이동형: 다음 장소의 주차장으로 안내 */
    fun goToNextStop() {
        val s = _ui.value
        val course = s.activeCourse ?: return
        val idx = when (val p = s.phase) { is RunPhase.Visiting -> p.stopIndex + 1; else -> return }
        if (idx >= course.stops.size) { finish(); return }
        val stop = course.stops[idx]
        val charger = course.charge?.takeIf { s.chargeChosen && it.stopIndex == idx }?.charger
        val target = charger?.let { NaviTarget(it.name, it.point, true) }
            ?: stop.parking?.let { NaviTarget(it.name, it.point, true) }
            ?: NaviTarget(stop.place.name, stop.place.point, false)
        requestRoute(idx, target)
    }

    /** 한 번 주차형: 걸어서 다음 장소로 (화면만 진행) */
    fun nextWalkStop() {
        val s = _ui.value
        val course = s.activeCourse ?: return
        val p = s.phase as? RunPhase.Visiting ?: return
        if (p.stopIndex + 1 >= course.stops.size) finish()
        else _ui.update { it.copy(phase = RunPhase.Visiting(p.stopIndex + 1)) }
    }

    /** 도착 처리 (내비 도착 이벤트, 수동 버튼) */
    fun markArrived() = arrive(auto = false)

    private fun arrive(auto: Boolean) {
        val s = _ui.value
        val p = s.phase as? RunPhase.DrivingTo ?: return
        autoArriveJob?.cancel()
        guideEndJob?.cancel()
        routeTimeoutJob?.cancel()
        val late = s.activeCourse?.stops?.getOrNull(p.stopIndex)?.let { delayMinutes(nowMinute(), it) }
        _ui.update {
            it.copy(
                phase = RunPhase.Visiting(p.stopIndex, autoArrived = auto, from = if (auto) p else null),
                delayMin = late ?: it.delayMin,
                toast = if (auto) "주차장 근처에 P단으로 세워 도착으로 처리했어요" else it.toast,
            )
        }
    }

    /** 자동 도착이 틀렸을 때 안내 단계로 되돌린다 (내비 안내는 계속 중이라 다시 요청하지 않는다) */
    fun undoAutoArrival() {
        val p = _ui.value.phase as? RunPhase.Visiting ?: return
        val back = p.from ?: return
        _ui.update { it.copy(phase = back) }
        // 그사이 내비 안내가 끝났거나 바뀌었을 수 있다: 지금도 이 주차장을 안내 중인지 확인하고,
        // 아니면 "다시 시도"를 누를 수 있게 실패로 표시한다
        val seq = requestSeq
        guideEndJob?.cancel()
        guideEndJob = viewModelScope.launch {
            val guiding = isGuidingTo(back.target)
            if (seq != requestSeq || _ui.value.phase != back) return@launch
            if (!guiding) failRoute("내비 안내가 끝나 있어요. 다시 시도하면 이 주차장으로 안내해요")
        }
    }

    /** 한 번 주차형: 코스를 마치고 차로 돌아가기 */
    fun finish() = _ui.update { it.copy(phase = RunPhase.Finished) }

    fun backToHome() {
        recommendJob?.cancel()
        routeTimeoutJob?.cancel()
        verifyJob?.cancel()
        autoArriveJob?.cancel()
        guideEndJob?.cancel()
        requestSeq++
        _ui.update { it.copy(screen = Screen.HOME, activeCourse = null, phase = null, loading = false, delayMin = null) }
    }
    fun backToResults() = _ui.update { it.copy(screen = Screen.RESULTS) }
    fun consumeToast() = _ui.update { it.copy(toast = null) }

    /**
     * SDK 이벤트에는 요청 ID가 없다. 그래서
     * 1) 이번 요청을 보내기 전에 받은 이벤트는 버리고,
     * 2) "안내 시작" 또는 "안내 상태 바뀜" 이벤트를 받으면 내비의 현재 상태를 직접 조회해서
     *    경로 안내 중(ROUTE_DRIVE)이고 목적지가 요청한 지점(100m 이내)일 때만 GUIDING으로 바꾼다.
     * 늦게 온 이전 요청의 콜백이 와도, 내비가 지금 우리 목적지로 안내 중이 아니면 확인되지 않는다.
     * 반대로 실패로 표시된 뒤라도 내비가 실제로 우리 목적지를 안내하고 있으면 GUIDING으로 되돌린다.
     */
    private fun onNaviEvent(e: NaviEvent) {
        val p = _ui.value.phase as? RunPhase.DrivingTo ?: return
        if (e.at < requestSentAt) return
        when (e) {
            is NaviEvent.RouteStarted -> if (p.route != RouteStatus.GUIDING) startVerify()
            is NaviEvent.StatusChanged ->
                if (e.guiding) {
                    // 안내 중에 다시 "안내 시작"이 오면 다른 목적지로 바뀌었을 수 있으니 목적지를 다시 확인한다
                    if (p.route != RouteStatus.GUIDING) startVerify()
                    else checkGuidanceEnded("내비가 다른 목적지를 안내하고 있어요. 다시 시도하면 이 코스로 안내해요", destinationChanged = true)
                } else if (p.route == RouteStatus.GUIDING) checkGuidanceEnded()
            is NaviEvent.Error -> if (p.route == RouteStatus.REQUESTING) {
                routeTimeoutJob?.cancel()
                failRoute(naviErrorText(e.message))
            }
            // 우리가 요청한 안내가 시작된 뒤의 도착만 처리한다
            is NaviEvent.Arrived -> if (p.route == RouteStatus.GUIDING && sameDestination(e.name, p.target.name)) markArrived()
            is NaviEvent.WaypointArrived -> Unit
        }
    }

    /**
     * 내비 안내가 꺼졌다 (사용자가 취소했거나, 다른 목적지로 바꿨거나, 도착해서 끝났거나).
     * 잠깐 기다려도 도착 처리가 안 됐고 내비가 우리 목적지를 안내하고 있지 않으면 "안내 끝남"으로 표시해
     * 다시 안내하거나 도착 처리할 수 있게 한다. 이전 목적지로 자동 도착하는 일도 막는다.
     */
    private fun checkGuidanceEnded(
        reason: String = "내비 안내가 끝났어요. 도착했다면 도착 처리를 눌러 주세요",
        /** 다른 목적지로 바뀌었을 수 있는 경우: 이전 목적지 기준 자동 도착은 기다리지 않고 취소한다 */
        destinationChanged: Boolean = false,
    ) {
        if (destinationChanged) autoArriveJob?.cancel()
        guideEndJob?.cancel()
        val seq = requestSeq
        guideEndJob = viewModelScope.launch {
            delay(GUIDE_END_GRACE_MS)
            // 주차장 입구에서 안내가 끝나며 P단에 넣은 경우: 자동 도착 판정을 먼저 끝까지 기다린다
            if (!destinationChanged) autoArriveJob?.takeIf { it.isActive }?.join()
            val p = _ui.value.phase as? RunPhase.DrivingTo ?: return@launch
            if (seq != requestSeq || p.route != RouteStatus.GUIDING) return@launch
            if (isGuidingTo(p.target)) return@launch
            if (seq != requestSeq || _ui.value.phase != p) return@launch
            failRoute(reason)
        }
    }

    /**
     * P단으로 5초 이상 세웠고 그 자리가 안내 중인 주차장에서 300m 안이면 도착으로 처리한다.
     * 내비 도착 이벤트가 안 오거나(이름이 달라서 등) 주차장 입구 앞에서 안내가 끝나는 경우를 메운다.
     * 위치를 모르면(내비 미연결·위치 없음) 아무것도 하지 않고 수동 도착을 쓴다.
     */
    private fun scheduleAutoArrival() {
        autoArriveJob?.cancel()
        val since = parkedAt ?: return
        if (since < requestSentAt) return  // 안내를 요청하기 전부터 서 있던 경우
        val gateway = routeGateway ?: return
        if (gateway !== c.navi || !c.navi.connected.value) return  // 데모 내비의 고정 위치로는 판정하지 않는다
        val seq = requestSeq
        autoArriveJob = viewModelScope.launch {
            delay(AUTO_ARRIVE_HOLD_MS)
            if (parkedAt != since || vehicle.value.drive != DriveState.PARKED) return@launch
            val p = _ui.value.phase as? RunPhase.DrivingTo ?: return@launch
            if (seq != requestSeq || p.route != RouteStatus.GUIDING) return@launch
            val here = gateway.currentLocation() ?: return@launch
            // 위치를 기다리는 동안 출발했거나 안내가 바뀌었으면 취소
            if (parkedAt != since || vehicle.value.drive != DriveState.PARKED) return@launch
            if (seq != requestSeq || _ui.value.phase != p) return@launch
            if (Geo.distanceM(here, p.target.point) > AUTO_ARRIVE_M) return@launch
            // 내비 목적지가 그사이 다른 곳으로 바뀌었으면 도착으로 보지 않는다 (안내가 끝나 목적지가 비었으면 허용)
            val dest = gateway.activeRoute()?.destination
            if (dest != null && Geo.distanceM(dest, p.target.point) > ROUTE_MATCH_M) return@launch
            if (parkedAt != since || seq != requestSeq || _ui.value.phase != p) return@launch
            arrive(auto = true)
        }
    }

    /** 확인 작업이 이미 돌고 있으면 새로 시작하지 않는다 */
    private fun startVerify() {
        if (verifyJob?.isActive == true) return
        val seq = requestSeq
        verifyJob = viewModelScope.launch {
            // 내비 상태가 이벤트보다 늦게 바뀔 수 있어, 확인될 때까지 1초 간격으로 조회한다 (최대 30초)
            repeat(30) { attempt ->
                if (attempt > 0) delay(1_000)
                val p = _ui.value.phase as? RunPhase.DrivingTo ?: return@launch
                if (seq != requestSeq || p.route == RouteStatus.GUIDING) return@launch
                if (isGuidingTo(p.target)) { markGuiding(seq); return@launch }
            }
        }
    }

    /** 내비가 지금 "경로 안내 중"이고 현재 목적지가 target 지점(100m 이내)인지 */
    private suspend fun isGuidingTo(target: NaviTarget): Boolean {
        val active = routeGateway?.activeRoute() ?: return false
        return active.guiding == true &&
            active.destination?.let { Geo.distanceM(it, target.point) <= ROUTE_MATCH_M } == true
    }

    /** 같은 요청(seq)이 아직 진행 중일 때만 안내 중으로 바꾼다 (실패 표시였어도 되돌림) */
    private fun markGuiding(seq: Int) {
        if (seq != requestSeq) return
        routeTimeoutJob?.cancel()
        _ui.update { s ->
            val p = s.phase as? RunPhase.DrivingTo ?: return@update s
            s.copy(phase = p.copy(route = RouteStatus.GUIDING, failReason = null))
        }
        // 안내 확인보다 P단 전환이 먼저 왔을 수 있다
        if (parkedAt != null) scheduleAutoArrival()
    }

    /** 내비가 알려 준 목적지 이름이 우리가 요청한 이름과 같은지 (공백·대소문자 무시) */
    private fun sameDestination(reported: String, requested: String): Boolean {
        fun norm(s: String) = s.filterNot { it.isWhitespace() }.lowercase()
        return reported.isNotBlank() && norm(reported) == norm(requested)
    }

    private fun naviErrorText(code: String) = when (code) {
        "CAUSE_LOW_BATTERY" -> "배터리가 부족해 경로를 만들 수 없어요"
        "CAUSE_SEARCH_ROUTE_OVER_DISTANCE" -> "거리가 너무 멀어 경로를 만들 수 없어요"
        "CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE" -> "이미 목적지 근처예요"
        else -> "내비가 경로를 찾지 못했어요 ($code)"
    }

    /**
     * 차로 이동형에서 장소를 둘러본 뒤 다시 출발(P → 주행)하면 다음 장소 안내를 자동으로 시작한다.
     */
    private fun onVehicle(v: VehicleState) {
        // P단에 들어간 시각을 기록하고, P에서 나오면 지운다
        if (v.drive == DriveState.PARKED) {
            if (parkedAt == null) {
                parkedAt = System.nanoTime()
                val p = _ui.value.phase
                if (p is RunPhase.DrivingTo && p.route == RouteStatus.GUIDING) scheduleAutoArrival()
            }
        } else {
            // 기어를 모르게 된 경우도 P 유지로 보지 않는다
            parkedAt = null
            autoArriveJob?.cancel()
        }
        // 후진·중립을 거쳐도(P→R→D) 출발로 본다. P→R→P(주차 조정)는 출발이 아니다
        val wasParked = lastSettled == DriveState.PARKED
        if (v.drive == DriveState.PARKED || v.drive == DriveState.DRIVING) lastSettled = v.drive
        val s = _ui.value
        if (wasParked && v.drive == DriveState.DRIVING &&
            s.activeCourse?.mode == CourseMode.DRIVE_AND_HOP && s.phase is RunPhase.Visiting
        ) {
            goToNextStop()
            _ui.update { it.copy(toast = "다시 출발해서 다음 장소 안내를 시작했어요") }
        }
    }

    // ───────────── 시연 패널 ─────────────

    fun setDemoDriving(driving: Boolean) { if (!c.isAutomotive) c.demoVehicle.setDriving(driving) }
    fun setDemoRain(on: Boolean) { c.demo.rain.value = on }
    fun setDemoLowEnergy(on: Boolean) { c.demo.lowEnergy.value = on }
    fun simulateArrival() {
        if (c.navi.connected.value) markArrived() else c.demoNavi.simulateArrival()
    }

    val weatherOfLast: Weather? get() = _ui.value.recommendation?.weather
}

/**
 * 계획 대비 지연(분). 비교 기준은 "주차장 도착 계획 시각" = 장소 도착 계획 − 주차장에서 걷는 시간.
 * 자정을 넘는 경우를 고려해 −12시간 ~ +12시간 범위로 맞춘다.
 */
internal fun delayMinutes(nowMinuteOfDay: Int, stop: com.recodate.drive.model.CourseStop): Int {
    val plannedPark = stop.arriveMinuteOfDay - Geo.walkMin(stop.walkInM)
    val d = ((nowMinuteOfDay - plannedPark) % 1440 + 1440) % 1440
    return if (d >= 720) d - 1440 else d
}
