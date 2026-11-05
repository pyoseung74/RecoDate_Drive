package com.recodate.drive

import android.content.Intent
import android.os.Bundle
import com.recodate.drive.model.CourseMode
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.recodate.drive.ui.DriveApp
import com.recodate.drive.ui.DriveTheme
import com.recodate.drive.ui.DriveViewModel

/**
 * 앱 프로세스 동안 살아 있는 ViewModel 보관소.
 * Pleos 홈은 내비를 전체 화면으로 띄울 때 앱 화면(Activity)을 닫았다가 돌아오면 새로 만든다.
 * Activity에 ViewModel을 묶으면 그때 진행 중인 코스가 사라지므로, 프로세스 단위로 보관한다.
 * (화면이 없는 동안에도 기어·내비 이벤트를 받아 자동 도착·다음 장소 안내가 이어진다)
 */
private object ProcessViewModels : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

class MainActivity : ComponentActivity() {

    private val vm: DriveViewModel by lazy {
        ViewModelProvider(ProcessViewModels, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DriveViewModel(AppContainer(applicationContext)) as T
        })[DriveViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DriveTheme { DriveApp(vm) } }
        handleDebugIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        vm.onAppVisible()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
    }

    /**
     * 디버그 빌드 전용 테스트 진입점:
     * adb shell am start -n com.recodate.drive/.MainActivity --es anchor "삼척해수욕장" --ed lat 37.43 --ed lon 129.18 --es mode DRIVE_AND_HOP --ez named true
     */
    private fun handleDebugIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        // 시연 녹화용: 내비 목적지를 장소로 설정해 두고 출발 화면을 다시 불러온다
        //   adb shell am start -n com.recodate.drive/.MainActivity --es naviDest "안목해변"
        intent.getStringExtra("naviDest")?.let { vm.debugSetNaviDestination(it); intent.removeExtra("naviDest"); return }
        if (!intent.hasExtra("anchor")) return
        val name = intent.getStringExtra("anchor") ?: return
        val mode = runCatching { CourseMode.valueOf(intent.getStringExtra("mode") ?: "") }.getOrDefault(CourseMode.PARK_AND_WALK)
        vm.debugRecommend(name, intent.getDoubleExtra("lat", 0.0), intent.getDoubleExtra("lon", 0.0), mode,
            intent.getBooleanExtra("named", true))
        intent.removeExtra("anchor")
    }
}
