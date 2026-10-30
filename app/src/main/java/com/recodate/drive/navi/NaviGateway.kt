package com.recodate.drive.navi

import android.content.Context
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import com.recodate.drive.data.DemoData
import com.recodate.drive.model.Anchor
import com.recodate.drive.model.GeoPoint
import com.recodate.drive.model.NaviTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

sealed interface NaviEvent {
    data class RouteStarted(val target: String) : NaviEvent
    data class Arrived(val name: String) : NaviEvent
    data class WaypointArrived(val name: String) : NaviEvent
    data class Error(val message: String) : NaviEvent
}

interface NaviGateway {
    /** NaviHelper 초기화에 성공했는지 */
    val connected: StateFlow<Boolean>
    val events: SharedFlow<NaviEvent>
    fun start()
    fun stop()
    suspend fun currentLocation(): GeoPoint?
    /** 내비에 이미 설정된 목적지 */
    suspend fun currentDestination(): Anchor?
    /** 최근 목적지 + 즐겨찾기 (중복 제거) */
    suspend fun recentDestinations(): List<Anchor>
    /**
     * 목적지 하나로 경로 요청. 요청을 보내지 못하면 false.
     * true여도 안내가 시작된 것은 아니다. RouteStarted 또는 Error 이벤트로 결과를 확인한다.
     */
    fun startRoute(target: NaviTarget): Boolean
    /** 목적지 + 경유지(최대 2곳)로 경로 요청. 의미는 startRoute와 같다 */
    fun startRouteWithWaypoints(destination: NaviTarget, waypoints: List<NaviTarget>): Boolean
}

/**
 * Pleos NaviHelper 구현.
 * get*Info() 결과는 리스너 콜백으로 오기 때문에 CompletableDeferred로 기다린다(최대 2.5초).
 * 권한: pleos.car.permission.NAVI_ROUTE, NAVI_ROUTE_SEARCH
 */
class PleosNaviGateway(context: Context) : NaviGateway {
    private val helper = NaviHelper(context.applicationContext)
    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected
    private val _events = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 8)
    override val events: SharedFlow<NaviEvent> = _events

    private var pendingLocation: CompletableDeferred<CurrentLocationInfo>? = null
    private var pendingDestination: CompletableDeferred<DestinationInfo?>? = null
    private var pendingRecent: CompletableDeferred<List<RecentDestinationInfo>>? = null
    private var pendingBookmark: CompletableDeferred<List<BookmarkInfo>>? = null

    private val listener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) { pendingLocation?.complete(info) }
        override fun onDestinationInfo(info: DestinationInfo) { pendingDestination?.complete(info) }
        override fun onRecentDestinationInfo(list: List<RecentDestinationInfo>) { pendingRecent?.complete(list) }
        override fun onBookmarkInfo(list: List<BookmarkInfo>) { pendingBookmark?.complete(list) }
        override fun onRouteStarted(info: RouteStartInfo) {
            Log.d(TAG, "onRouteStarted name='${info.name}'")
            _events.tryEmit(NaviEvent.RouteStarted(info.name))
        }
        override fun onDestinationArrived(info: DestinationArrivedInfo) {
            Log.d(TAG, "onDestinationArrived name='${info.name}'")
            _events.tryEmit(NaviEvent.Arrived(info.name))
        }
        override fun onWayPointArrived(info: ai.pleos.playground.navi.data.WaypointArrivedInfo) {
            _events.tryEmit(NaviEvent.WaypointArrived(""))
        }
        override fun onError(code: NaviErrorCode) { _events.tryEmit(NaviEvent.Error(code.name)) }
    }

    override fun start() {
        helper.initialize(
            {
                helper.addListener(listener)
                _connected.value = true
            },
            { t -> Log.w(TAG, "NaviHelper init failed", t); _connected.value = false },
        )
    }

    override fun stop() {
        runCatching { helper.removeListener(listener) }
        runCatching { helper.release() }
    }

    override suspend fun currentLocation(): GeoPoint? {
        if (!_connected.value) return null
        val d = CompletableDeferred<CurrentLocationInfo>().also { pendingLocation = it }
        runCatching { helper.getCurrentLocationInfo() }.onFailure { return null }
        return withTimeoutOrNull(2_500) { d.await() }
            ?.takeIf { !(it.latitude == 0.0 && it.longitude == 0.0) }
            ?.let { Log.d(TAG, "current location ${it.latitude},${it.longitude}"); GeoPoint(it.latitude, it.longitude) }
    }

    override suspend fun currentDestination(): Anchor? {
        if (!_connected.value) return null
        val d = CompletableDeferred<DestinationInfo?>().also { pendingDestination = it }
        runCatching { helper.getDestinationInfo() }.onFailure { return null }
        val info = withTimeoutOrNull(2_500) { d.await() } ?: return null
        if ((info.latitude == 0.0 && info.longitude == 0.0) || info.name.isBlank()) return null
        return Anchor(info.name, GeoPoint(info.latitude, info.longitude), place = null)
    }

    override suspend fun recentDestinations(): List<Anchor> {
        if (!_connected.value) return emptyList()
        val r = CompletableDeferred<List<RecentDestinationInfo>>().also { pendingRecent = it }
        val b = CompletableDeferred<List<BookmarkInfo>>().also { pendingBookmark = it }
        runCatching { helper.getRecentDestinationInfo(); helper.getBookmarkInfo() }
        val recent = withTimeoutOrNull(2_500) { r.await() }.orEmpty()
            .map { Anchor(it.name, GeoPoint(it.latitude, it.longitude)) }
        val marks = withTimeoutOrNull(1_000) { b.await() }.orEmpty()
            .map { Anchor(it.name, GeoPoint(it.latitude, it.longitude)) }
        // 이름이 비었거나 좌표가 (0,0)인 빈 항목은 뺀다
        return (marks + recent)
            .filter { it.name.isNotBlank() && !(it.point.lat == 0.0 && it.point.lon == 0.0) }
            .distinctBy { it.name }.take(6)
    }

    override fun startRoute(target: NaviTarget): Boolean {
        if (!_connected.value) return false
        return runCatching { helper.requestRoute(target.toRouteInfo()) }
            .onFailure { Log.w(TAG, "requestRoute failed", it) }
            .isSuccess
    }

    override fun startRouteWithWaypoints(destination: NaviTarget, waypoints: List<NaviTarget>): Boolean {
        if (!_connected.value) return false
        val indices = listOf(WaypointIndex.FIRST, WaypointIndex.SECOND)
        val wps = waypoints.take(2).mapIndexed { i, w ->
            RequestWaypointInfo(
                latitude = w.point.lat,
                longitude = w.point.lon,
                poiName = w.name,
                waypointIndex = indices[i],
                poiId = "",
                address = "",
                poiSubId = "0",
            )
        }
        val ok = runCatching { helper.requestRouteWithWaypoints(RouteWithWaypointsRequest(destination.toRouteInfo(), wps)) }.isSuccess
        // 실패하면 다음 목적지 하나만 안내
        return ok || startRoute(waypoints.firstOrNull() ?: destination)
    }

    private fun NaviTarget.toRouteInfo() = RouteInfo(
        latitude = point.lat,
        longitude = point.lon,
        displayLatitude = null,
        displayLongitude = null,
        poiName = name,
        poiId = "",
        address = "",
        poiSubId = "0",
        routeOption = RouteOption.RECOMMENDED,
    )

    companion object { private const val TAG = "PleosNavi" }
}

/** 데모 내비: 강릉역에서 출발, 시연 패널의 "도착" 버튼으로 도착 이벤트를 보낸다 */
class DemoNaviGateway : NaviGateway {
    override val connected: StateFlow<Boolean> = MutableStateFlow(false)
    private val _events = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 8)
    override val events: SharedFlow<NaviEvent> = _events
    var lastTarget: NaviTarget? = null
        private set

    override fun start() {}
    override fun stop() {}
    override suspend fun currentLocation() = DemoData.demoCarLocation
    override suspend fun currentDestination(): Anchor? = null
    override suspend fun recentDestinations() = DemoData.recentAnchors

    override fun startRoute(target: NaviTarget): Boolean {
        lastTarget = target
        _events.tryEmit(NaviEvent.RouteStarted(target.name))
        return true
    }

    override fun startRouteWithWaypoints(destination: NaviTarget, waypoints: List<NaviTarget>) =
        startRoute(waypoints.firstOrNull() ?: destination)

    fun simulateArrival() {
        lastTarget?.let { _events.tryEmit(NaviEvent.Arrived(it.name)) }
    }
}
