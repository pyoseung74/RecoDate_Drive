package com.recodate.drive

import android.os.Bundle
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
    }
}
