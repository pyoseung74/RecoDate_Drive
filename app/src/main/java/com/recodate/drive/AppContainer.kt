package com.recodate.drive

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.recodate.drive.data.CourseService
import com.recodate.drive.data.DemoParkingSource
import com.recodate.drive.data.DemoPlaceSource
import com.recodate.drive.data.DemoWeatherSource
import com.recodate.drive.data.FallbackParkingSource
import com.recodate.drive.data.FallbackPlaceSource
import com.recodate.drive.data.KmaWeatherSource
import com.recodate.drive.data.NoRealtimeParking
import com.recodate.drive.data.NoTraffic
import com.recodate.drive.data.TmapParkingSource
import com.recodate.drive.data.TmapPlaceSource
import com.recodate.drive.data.TmapTrafficSource
import com.recodate.drive.data.WeatherSource
import com.recodate.drive.model.GeoPoint
import com.recodate.drive.navi.DemoNaviGateway
import com.recodate.drive.navi.NaviGateway
import com.recodate.drive.navi.PleosNaviGateway
import com.recodate.drive.vehicle.DemoVehicleGateway
import com.recodate.drive.vehicle.PleosVehicleGateway
import com.recodate.drive.vehicle.VehicleGateway
import kotlinx.coroutines.flow.MutableStateFlow

/** 시연 패널에서 바꾸는 값 */
class DemoSwitches {
    val rain = MutableStateFlow(false)
    val lowEnergy = MutableStateFlow(false)
}

/**
 * 실제 차량(Pleos Connect)이면 Pleos SDK를, 아니면 데모 구현을 쓴다.
 * API 키가 없으면 해당 데이터는 데모 데이터로 대체한다.
 */
class AppContainer(context: Context) {
    val isAutomotive: Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)

    val demo = DemoSwitches()
    val hasTmapKey = BuildConfig.TMAP_APP_KEY.isNotBlank()
    val hasDataKey = BuildConfig.DATA_GO_KR_KEY.isNotBlank()

    val demoVehicle = DemoVehicleGateway()
    val demoNavi = DemoNaviGateway()

    val vehicle: VehicleGateway = if (isAutomotive) safely { PleosVehicleGateway(context) } ?: demoVehicle else demoVehicle
    val navi: NaviGateway = if (isAutomotive) safely { PleosNaviGateway(context) } ?: demoNavi else demoNavi

    private val liveWeather: WeatherSource? = if (hasDataKey) KmaWeatherSource(BuildConfig.DATA_GO_KR_KEY) else null
    private val demoWeather = DemoWeatherSource { demo.rain.value }

    val courseService = CourseService(
        places = if (hasTmapKey) FallbackPlaceSource(TmapPlaceSource(BuildConfig.TMAP_APP_KEY), DemoPlaceSource()) else DemoPlaceSource(),
        parking = if (hasTmapKey) FallbackParkingSource(TmapParkingSource(BuildConfig.TMAP_APP_KEY), DemoParkingSource()) else DemoParkingSource(),
        realtime = NoRealtimeParking(),
        traffic = if (hasTmapKey) TmapTrafficSource(BuildConfig.TMAP_APP_KEY) else NoTraffic(),
        weather = object : WeatherSource {
            // "비 오는 날" 시연을 켜면 실제 예보 대신 데모 날씨를 쓴다
            override suspend fun forecast(at: GeoPoint, minuteOfDay: Int) =
                if (demo.rain.value || liveWeather == null) demoWeather.forecast(at, minuteOfDay)
                else liveWeather.forecast(at, minuteOfDay)
        },
    )

    private fun <T> safely(block: () -> T): T? =
        runCatching(block).onFailure { Log.w("AppContainer", "Pleos SDK unavailable", it) }.getOrNull()
}
