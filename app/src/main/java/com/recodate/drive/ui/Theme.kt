package com.recodate.drive.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.recodate.drive.model.CongestionLevel

object C {
    val bg = Color(0xFF0E1116)
    val surface = Color(0xFF181C23)
    val surface2 = Color(0xFF222833)
    val line = Color(0xFF2E3644)
    val text = Color(0xFFF2F4F7)
    val sub = Color(0xFFA3ACBA)
    val accent = Color(0xFFFF6B8A)      // RecoDate 핑크
    val accentDim = Color(0xFF3A2129)
    val good = Color(0xFF3DD68C)
    val normal = Color(0xFFF5C451)
    val busy = Color(0xFFFF8A3D)
    val full = Color(0xFFFF4D4F)
    val info = Color(0xFF5AA9FF)
}

fun CongestionLevel.color(): Color = when (this) {
    CongestionLevel.FREE -> C.good
    CongestionLevel.NORMAL -> C.normal
    CongestionLevel.BUSY -> C.busy
    CongestionLevel.FULL -> C.full
    CongestionLevel.UNKNOWN -> C.sub
}

/** 차량 화면(2560×1440, 160dpi)용 큰 글씨 */
private val carType = Typography(
    displaySmall = TextStyle(fontSize = 52.sp, fontWeight = FontWeight.Bold, lineHeight = 62.sp),
    headlineMedium = TextStyle(fontSize = 38.sp, fontWeight = FontWeight.Bold, lineHeight = 48.sp),
    titleLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.SemiBold, lineHeight = 40.sp),
    titleMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, lineHeight = 36.sp),
    bodyLarge = TextStyle(fontSize = 26.sp, lineHeight = 36.sp),
    bodyMedium = TextStyle(fontSize = 23.sp, lineHeight = 32.sp),
    labelLarge = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun DriveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.accent, background = C.bg, surface = C.surface,
            onPrimary = Color.White, onBackground = C.text, onSurface = C.text,
        ),
        typography = carType,
    ) {
        CompositionLocalProvider(LocalContentColor provides C.text, content = content)
    }
}
