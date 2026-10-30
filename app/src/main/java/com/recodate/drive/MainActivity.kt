package com.recodate.drive

import android.content.Intent
import android.os.Bundle
import com.recodate.drive.model.CourseMode
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.recodate.drive.ui.DriveApp
import com.recodate.drive.ui.DriveTheme
import com.recodate.drive.ui.DriveViewModel

class MainActivity : ComponentActivity() {

    private val vm: DriveViewModel by lazy {
        ViewModelProvider(this, object : ViewModelProvider.Factory {
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
    }

    /**
     * 디버그 빌드 전용 테스트 진입점:
     * adb shell am start -n com.recodate.drive/.MainActivity --es anchor "삼척해수욕장" --ed lat 37.43 --ed lon 129.18 --es mode DRIVE_AND_HOP --ez named true
     */
    private fun handleDebugIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null || !intent.hasExtra("anchor")) return
        val name = intent.getStringExtra("anchor") ?: return
        val mode = runCatching { CourseMode.valueOf(intent.getStringExtra("mode") ?: "") }.getOrDefault(CourseMode.PARK_AND_WALK)
        vm.debugRecommend(name, intent.getDoubleExtra("lat", 0.0), intent.getDoubleExtra("lon", 0.0), mode,
            intent.getBooleanExtra("named", true))
        intent.removeExtra("anchor")
    }
}
