package com.recodate.drive.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recodate.drive.AppContainer
import com.recodate.drive.data.MY_AREA
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
    data class Visiting(val stopIndex: Int) : RunPhase
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
)

class DriveViewModel(private val c: AppContainer) : ViewModel() {

    companion object { const val ROUTE_TIMEOUT_MS = 25_000L }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    /** 실제 차량 값이 들어오면 그것을, 아니면 데모 값을 쓴다. 시연 스위치(배터리 낮음)도 반영 */
    val vehicle: StateFlow<VehicleState> =
        combine(c.vehicle.state, c.demoVehicle.state, c.demo.lowEnergy) { real, demo, low ->
            val base = if (real.connected) real else demo.copy(connected = false)
            if (low) base.copy(energy = VehicleEnergy(evSocPercent = 12, fuelPercent = null)) else base
        }.stateIn(viewModelScope, SharingStarted.Eagerly, VehicleState())

    val rainDemo: StateFlow<Boolean> = c.demo.rain
    val lowEnergyDemo: StateFlow<Boolean> = c.demo.lowEnergy
    val naviConnected: StateFlow<Boolean> = c.navi.connected
    val usingLiveKeys: Boolean get() = c.hasTmapKey || c.hasDataKey

    private val navi: NaviGateway get() = if (c.navi.connected.value) c.navi else c.demoNavi
    private var recommendJob: Job? = null
    private var routeTimeoutJob: Job? = null
    private var lastDrive: DriveState = DriveState.UNKNOWN

    init {
        c.vehicle.start()
        c.navi.start()
        viewModelScope.launch { merge(c.navi.events, c.demoNavi.events).collect(::onNaviEvent) }
        viewModelScope.launch { vehicle.collect(::onVehicle) }
        loadAnchors()
    }

    override fun onCleared() {
        c.vehicle.stop()
        c.navi.stop()
    }

    // ───────────── 출발 화면 ─────────────

    fun loadAnchors() = viewModelScope.launch {
        // Pleos 차량이면 NaviHelper 연결을 잠깐 기다린다 (초기화는 비동기)
        if (c.isAutomotive) withTimeoutOrNull(4_000) { c.navi.connected.first { it } }
        val n = navi
        val destJob = async { n.currentDestination() }
        val recentJob = async { n.recentDestinations() }
        val hereJob = async { n.currentLocation() }
        val dest = destJob.await()
        // 실제 내비에 최근 목적지가 없으면 시연용 강릉 목적지를 함께 보여 준다
        val recent = recentJob.await().ifEmpty { c.demoNavi.recentDestinations() }
        val here = (hereJob.await() ?: c.demoNavi.currentLocation()).let { Anchor(MY_AREA, it) }
        val list = listOfNotNull(dest, here) + recent.filter { it.name != dest?.name }
        _ui.update {
            it.copy(
                naviDestination = dest,
                anchors = list,
                selectedAnchor = it.selectedAnchor ?: dest ?: recent.firstOrNull() ?: here,
            )
        }
    }

    fun selectAnchor(a: Anchor) = _ui.update { it.copy(selectedAnchor = a) }

    fun selectMode(m: CourseMode) = _ui.update { it.copy(mode = m, modeChosenByUser = true) }

    fun toggleInterest(i: Interest) = _ui.update {
        it.copy(interests = if (i in it.interests) it.interests - i else it.interests + i)
    }

    fun recommend(refresh: Boolean = false) {
        val s = _ui.value
        val anchor = s.selectedAnchor ?: return
        recommendJob?.cancel()
        recommendJob = viewModelScope.launch {
            val variation = if (refresh) s.variation + 1 else 0
            _ui.update { it.copy(loading = true, error = null, variation = variation) }
            runCatching {
                // 내비가 아직 준비 중이면 데모 위치(강릉역)를 출발지로 쓴다
                val origin: GeoPoint? = navi.currentLocation() ?: c.demoNavi.currentLocation()
                var rec = c.courseService.recommend(anchor, s.mode, s.interests, origin, vehicle.value.energy, variation)
                // 사용자가 모드를 고르지 않았고 날씨가 나쁘면 "차로 이동"으로 자동 전환
                if (!s.modeChosenByUser && s.mode == CourseMode.PARK_AND_WALK && rec.weather?.isHarsh == true) {
                    rec = c.courseService.recommend(anchor, CourseMode.DRIVE_AND_HOP, s.interests, origin, vehicle.value.energy, variation)
                    _ui.update { it.copy(mode = CourseMode.DRIVE_AND_HOP, toast = "날씨가 좋지 않아 '차로 이동' 코스로 추천했어요") }
                }
                rec
            }.onSuccess { rec ->
                _ui.update {
                    it.copy(
                        loading = false,
                        recommendation = rec,
                        screen = Screen.RESULTS,
                        error = if (rec.courses.isEmpty()) "조건에 맞는 코스를 찾지 못했어요. 모드나 관심사를 바꿔 보세요." else null,
                    )
                }
            }.onFailure { e ->
                _ui.update { it.copy(loading = false, error = "추천 중 문제가 생겼어요: ${e.message}") }
            }
        }
    }

    // ───────────── 실행 ─────────────

    fun startCourse(course: Course) {
        _ui.update { it.copy(activeCourse = course, screen = Screen.RUN) }
        requestRoute(0, course.firstNaviTarget)
    }

    /**
     * 경로를 요청하고 "요청 중"으로 둔다. 내비가 RouteStarted를 보내면 "안내 중",
     * Error를 보내거나 25초 안에 답이 없으면 "실패"가 된다. (에뮬레이터에서 시작까지 약 7초)
     * Pleos 차량에서는 내비가 연결되지 않았을 때 데모 내비로 넘어가지 않는다.
     */
    private fun requestRoute(stopIndex: Int, target: NaviTarget) {
        routeTimeoutJob?.cancel()
        val gateway: NaviGateway? = when {
            c.navi.connected.value -> c.navi
            !c.isAutomotive -> c.demoNavi
            else -> null
        }
        _ui.update { it.copy(phase = RunPhase.DrivingTo(stopIndex, target, RouteStatus.REQUESTING)) }
        val sent = gateway?.startRoute(target) ?: false
        if (!sent) {
            failRoute(if (gateway == null) "차량 내비에 연결되지 않았어요" else "내비에 경로 요청을 보내지 못했어요")
            return
        }
        routeTimeoutJob = viewModelScope.launch {
            delay(ROUTE_TIMEOUT_MS)
            val p = _ui.value.phase
            if (p is RunPhase.DrivingTo && p.route == RouteStatus.REQUESTING) failRoute("내비가 응답하지 않아요")
        }
    }

    private fun failRoute(reason: String) = _ui.update { s ->
        val p = s.phase as? RunPhase.DrivingTo ?: return@update s
        s.copy(phase = p.copy(route = RouteStatus.FAILED, failReason = reason))
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
        val target = stop.parking?.let { NaviTarget(it.name, it.point, true) } ?: NaviTarget(stop.place.name, stop.place.point, false)
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

    /** 수동 도착 처리 (내비 이벤트가 오지 않을 때) */
    fun markArrived() {
        val p = _ui.value.phase as? RunPhase.DrivingTo ?: return
        _ui.update { it.copy(phase = RunPhase.Visiting(p.stopIndex)) }
    }

    /** 한 번 주차형: 코스를 마치고 차로 돌아가기 */
    fun finish() = _ui.update { it.copy(phase = RunPhase.Finished) }

    fun backToHome() = _ui.update { it.copy(screen = Screen.HOME, activeCourse = null, phase = null) }
    fun backToResults() = _ui.update { it.copy(screen = Screen.RESULTS) }
    fun consumeToast() = _ui.update { it.copy(toast = null) }

    private fun onNaviEvent(e: NaviEvent) {
        val p = _ui.value.phase as? RunPhase.DrivingTo ?: return
        when (e) {
            is NaviEvent.RouteStarted -> if (p.route == RouteStatus.REQUESTING) {
                routeTimeoutJob?.cancel()
                _ui.update { it.copy(phase = p.copy(route = RouteStatus.GUIDING, failReason = null)) }
            }
            is NaviEvent.Error -> if (p.route != RouteStatus.FAILED) {
                routeTimeoutJob?.cancel()
                failRoute(naviErrorText(e.message))
            }
            // 우리가 요청한 안내가 시작된 뒤의 도착만 처리한다
            is NaviEvent.Arrived -> if (p.route == RouteStatus.GUIDING) markArrived()
            is NaviEvent.WaypointArrived -> Unit
        }
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
        val wasParked = lastDrive == DriveState.PARKED
        lastDrive = v.drive
        val s = _ui.value
        if (wasParked && v.drive == DriveState.DRIVING &&
            s.activeCourse?.mode == CourseMode.DRIVE_AND_HOP && s.phase is RunPhase.Visiting
        ) {
            goToNextStop()
            _ui.update { it.copy(toast = "다시 출발해서 다음 장소 안내를 시작했어요") }
        }
    }

    // ───────────── 시연 패널 ─────────────

    fun setDemoDriving(driving: Boolean) = c.demoVehicle.setDriving(driving)
    fun setDemoRain(on: Boolean) { c.demo.rain.value = on }
    fun setDemoLowEnergy(on: Boolean) { c.demo.lowEnergy.value = on }
    fun simulateArrival() {
        if (c.navi.connected.value) markArrived() else c.demoNavi.simulateArrival()
    }

    val weatherOfLast: Weather? get() = _ui.value.recommendation?.weather
}
