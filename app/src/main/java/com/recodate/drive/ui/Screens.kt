package com.recodate.drive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.recodate.drive.model.Course
import com.recodate.drive.model.CourseMode
import com.recodate.drive.model.Interest
import com.recodate.drive.model.OccupancySource
import com.recodate.drive.model.ParkingStatus
import com.recodate.drive.model.toClock
import com.recodate.drive.vehicle.DriveState
import com.recodate.drive.vehicle.VehicleState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun DriveApp(vm: DriveViewModel) {
    val ui by vm.ui.collectAsState()
    val vehicle by vm.vehicle.collectAsState()
    var demoOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 32.dp)) {
            StatusBar(vm, vehicle, onDemo = { demoOpen = true })
            Spacer(Modifier.height(28.dp))
            Box(Modifier.weight(1f)) {
                when (ui.screen) {
                    Screen.HOME -> HomeScreen(vm, ui, vehicle)
                    Screen.RESULTS -> ResultsScreen(vm, ui, vehicle)
                    Screen.RUN -> RunScreen(vm, ui, vehicle)
                }
            }
        }
        ui.toast?.let { msg ->
            LaunchedEffect(msg) { delay(3_500); vm.consumeToast() }
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp)
                    .clip(RoundedCornerShape(20.dp)).background(C.surface2).padding(horizontal = 40.dp, vertical = 22.dp),
            ) { Text(msg, style = MaterialTheme.typography.titleMedium, color = C.text) }
        }
        if (demoOpen) DemoPanel(vm, vehicle) { demoOpen = false }
    }
}

// ───────────────────────── 상단 상태 표시줄 ─────────────────────────

@Composable
private fun StatusBar(vm: DriveViewModel, v: VehicleState, onDemo: () -> Unit) {
    val naviOn by vm.naviConnected.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("RecoDate", style = MaterialTheme.typography.headlineMedium, color = C.accent)
        Text("  Drive", style = MaterialTheme.typography.headlineMedium, color = C.text)
        Spacer(Modifier.weight(1f))
        val (driveText, driveColor) = when (v.drive) {
            DriveState.PARKED -> "정차 (P)" to C.good
            DriveState.DRIVING -> "주행 중 · 간단 모드" to C.normal
            DriveState.UNKNOWN -> "기어 확인 중" to C.sub
        }
        Pill(Icons.Filled.DirectionsCar, driveText, driveColor)
        v.energy.percent?.let {
            Spacer(Modifier.width(16.dp))
            val label = (if (v.energy.isEv) "배터리 " else "연료 ") + "$it%"
            Pill(Icons.Filled.BatteryAlert, label, if (v.energy.isLow) C.full else C.sub)
        }
        Spacer(Modifier.width(16.dp))
        Pill(Icons.Filled.Navigation, if (v.connected || naviOn) "Pleos 연결됨" else "데모 차량", if (v.connected || naviOn) C.info else C.sub)
        Spacer(Modifier.width(16.dp))
        Box(
            Modifier.clip(CircleShape).background(C.surface2).clickable(onClick = onDemo).padding(18.dp),
        ) { Icon(Icons.Filled.Science, "시연 패널", tint = C.sub, modifier = Modifier.size(36.dp)) }
    }
}

@Composable
private fun Pill(icon: ImageVector, text: String, color: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(40.dp)).background(C.surface).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

// ───────────────────────── 1. 출발 화면 ─────────────────────────

@Composable
private fun HomeScreen(vm: DriveViewModel, ui: UiState, v: VehicleState) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
        Column(Modifier.weight(1.4f).fillMaxHeight()) {
            Text("어디 주변으로 갈까요?", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(24.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (ui.anchors.isEmpty()) Text("목적지를 불러오는 중…", color = C.sub, style = MaterialTheme.typography.bodyLarge)
                ui.anchors.forEach { a ->
                    val selected = a == ui.selectedAnchor
                    val tag = when {
                        a == ui.naviDestination -> "내비 목적지"
                        a.place == null && a.name.contains("주변") -> "현재 위치"
                        else -> "최근·즐겨찾기"
                    }
                    BigOption(
                        selected = selected,
                        onClick = { vm.selectAnchor(a) },
                        icon = Icons.Filled.Place,
                        title = a.name,
                        subtitle = tag,
                    )
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Text("코스 방식", style = MaterialTheme.typography.titleLarge, color = C.sub)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CourseMode.entries.forEach { m ->
                    BigOption(
                        modifier = Modifier.weight(1f),
                        selected = ui.mode == m,
                        onClick = { vm.selectMode(m) },
                        icon = if (m == CourseMode.PARK_AND_WALK) Icons.Filled.DirectionsWalk else Icons.Filled.DirectionsCar,
                        title = m.label,
                        subtitle = m.shortDesc,
                    )
                }
            }
            if (!ui.modeChosenByUser) {
                Spacer(Modifier.height(10.dp))
                Text("비·한파·폭염이면 '차로 이동'으로 자동 전환해요", color = C.sub, style = MaterialTheme.typography.bodyMedium)
            }
            // 관심사 칩은 정차 중에만 보여 준다 (주행 중 조작 최소화)
            if (!v.restricted) {
                Spacer(Modifier.height(36.dp))
                Text("원하는 것 (선택)", style = MaterialTheme.typography.titleLarge, color = C.sub)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Interest.entries.forEach { i ->
                        Chip(i.label, i in ui.interests) { vm.toggleInterest(i) }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            ui.error?.let { Text(it, color = C.full, style = MaterialTheme.typography.bodyLarge); Spacer(Modifier.height(12.dp)) }
            PrimaryButton(
                text = if (ui.loading) "주차·교통·날씨 확인 중…" else "코스 추천 받기",
                enabled = !ui.loading && ui.selectedAnchor != null,
                loading = ui.loading,
            ) { vm.recommend() }
        }
    }
}

// ───────────────────────── 2. 추천 화면 ─────────────────────────

@Composable
private fun ResultsScreen(vm: DriveViewModel, ui: UiState, v: VehicleState) {
    val rec = ui.recommendation ?: return
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButtonBig(Icons.Filled.ArrowBack, "뒤로") { vm.backToHome() }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text("${rec.request.anchor.name} 주변 · ${rec.request.mode.label}", style = MaterialTheme.typography.headlineMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val w = rec.weather
                val weatherText = when {
                    w == null -> "날씨 정보 없음"
                    w.precipitating -> "비 · ${w.temperatureC?.roundToInt() ?: "-"}℃"
                    else -> "강수확률 ${w.rainProbability ?: "-"}% · ${w.temperatureC?.roundToInt() ?: "-"}℃"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.WbCloudy, null, tint = C.sub, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        weatherText + (if (rec.usedDemoData) "   ·   데모 데이터" else "") +
                            (if (vm.tmapLimited()) "   ·   TMAP 오늘 한도 도달 → 예상값 사용" else ""),
                        color = C.sub, style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (!v.restricted) IconButtonBig(Icons.Filled.Refresh, "다른 코스") { vm.recommend(refresh = true) }
        }
        Spacer(Modifier.height(24.dp))
        ui.error?.let { Text(it, color = C.full, style = MaterialTheme.typography.titleMedium) }
        // 주행 중에는 1순위 코스 하나만 크게 보여 준다
        val shown = if (v.restricted) rec.courses.take(1) else rec.courses
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            shown.forEachIndexed { i, course ->
                CourseCard(course, rank = i + 1, compact = v.restricted, modifier = Modifier.weight(1f).fillMaxHeight()) {
                    vm.startCourse(course)
                }
            }
            if (v.restricted && rec.courses.size > 1) {
                Column(Modifier.weight(0.6f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    Text("정차하면 다른 코스 ${rec.courses.size - 1}개도 볼 수 있어요", color = C.sub, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun CourseCard(course: Course, rank: Int, compact: Boolean, modifier: Modifier, onStart: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(28.dp)).background(C.surface)
            .border(2.dp, if (rank == 1) C.accent else C.line, RoundedCornerShape(28.dp))
            .padding(32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (rank == 1) "추천 1순위" else "추천 ${rank}", color = if (rank == 1) C.accent else C.sub,
                style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            val eta = course.driveToStartMin?.let { "${it}분 후 도착" + if (course.driveToStartIsLive) " (실시간)" else " (예상)" }
            eta?.let { Text(it, color = C.info, style = MaterialTheme.typography.titleMedium) }
        }
        Spacer(Modifier.height(16.dp))
        course.mainParking?.let { lot ->
            if (lot.id == com.recodate.drive.engine.CourseRecommender.NO_LOT_ID) ParkingLine("${lot.name} 근처 (주차장 정보 없음)", null)
            else ParkingLine(lot.name, course.mainParkingStatus)
            Spacer(Modifier.height(16.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            course.stops.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(C.accentDim), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = C.accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.place.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val move = if (course.mode == CourseMode.DRIVE_AND_HOP) {
                            (if (s.driveInMin > 0) "차로 ${s.driveInMin}분 · " else "") + "주차 후 도보 ${s.walkInM}m"
                        } else "도보 ${s.walkInM}m"
                        Text("${s.arriveMinuteOfDay.toClock()} · ${s.place.category.label} · $move", color = C.sub,
                            style = MaterialTheme.typography.bodyMedium)
                        if (!compact && s.parking != null) ParkingLine(s.parking.name, s.parkingStatus, small = true)
                    }
                }
            }
            if (!compact) {
                Spacer(Modifier.height(4.dp))
                course.reasons.forEach { Text("· $it", color = C.text, style = MaterialTheme.typography.bodyMedium) }
                course.warnings.forEach { Text("⚠ $it", color = C.busy, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("종료 예상 ${course.endMinuteOfDay.toClock()} · 총 도보 ${course.totalWalkM}m", color = C.sub, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        PrimaryButton("이 코스로 안내 시작", enabled = true, onClick = onStart)
    }
}

@Composable
private fun ParkingLine(name: String, status: ParkingStatus?, small: Boolean = false) {
    val level = status?.level
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.LocalParking, null, tint = C.info, modifier = Modifier.size(if (small) 24.dp else 32.dp))
        Spacer(Modifier.width(8.dp))
        Text(name, style = if (small) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
            color = if (small) C.sub else C.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (level != null) {
            Spacer(Modifier.width(12.dp))
            val prefix = if (status.source == OccupancySource.REALTIME) "" else "${status.source.label} "
            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(level.color().copy(alpha = 0.18f)).padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text(prefix + level.label, color = level.color(), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ───────────────────────── 3. 실행 화면 ─────────────────────────

@Composable
private fun RunScreen(vm: DriveViewModel, ui: UiState, v: VehicleState) {
    val course = ui.activeCourse ?: return
    val phase = ui.phase
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
        // 왼쪽: 코스 진행 상황
        Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(28.dp)).background(C.surface).padding(36.dp)) {
            Text(course.mode.label + " 코스", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(24.dp))
            val current = when (phase) {
                is RunPhase.DrivingTo -> phase.stopIndex
                is RunPhase.Visiting -> phase.stopIndex
                else -> course.stops.size
            }
            course.mainParking?.let {
                StepRow("P", it.name, "주차", done = phase !is RunPhase.DrivingTo, active = phase is RunPhase.DrivingTo)
            }
            course.stops.forEachIndexed { i, s ->
                StepRow("${i + 1}", s.place.name, "${s.arriveMinuteOfDay.toClock()} · ${s.place.category.label}",
                    done = i < current, active = i == current && phase is RunPhase.Visiting)
            }
        }
        // 오른쪽: 지금 할 일 (큰 버튼)
        Column(Modifier.weight(1.3f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            when (phase) {
                is RunPhase.DrivingTo -> {
                    val (label, color) = when (phase.route) {
                        RouteStatus.REQUESTING -> "내비에 경로 요청 중…" to C.sub
                        RouteStatus.GUIDING -> "안내 중" to C.info
                        RouteStatus.FAILED -> "안내를 시작하지 못했어요" to C.full
                    }
                    Text(label, color = color, style = MaterialTheme.typography.titleLarge)
                    Text(phase.target.name, style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        when {
                            phase.route == RouteStatus.FAILED -> phase.failReason ?: ""
                            phase.target.isParking -> "장소 앞이 아니라 주차장으로 바로 안내해요"
                            else -> "목적지로 안내해요"
                        },
                        color = if (phase.route == RouteStatus.FAILED) C.full else C.sub,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(48.dp))
                    when (phase.route) {
                        RouteStatus.FAILED -> PrimaryButton("다시 시도", enabled = true) { vm.retryRoute() }
                        RouteStatus.GUIDING -> SecondaryButton("도착했어요") { vm.markArrived() }
                        RouteStatus.REQUESTING -> CircularProgressIndicator(color = C.accent, modifier = Modifier.size(56.dp))
                    }
                    if (phase.route == RouteStatus.FAILED) {
                        Spacer(Modifier.height(14.dp))
                        SecondaryButton("내비 없이 도착 처리") { vm.markArrived() }
                    }
                }
                is RunPhase.Visiting -> {
                    val stop = course.stops[phase.stopIndex]
                    val isLast = phase.stopIndex == course.stops.lastIndex
                    Text(if (course.mode == CourseMode.PARK_AND_WALK) "걸어서 이동" else "주차 완료", color = C.good,
                        style = MaterialTheme.typography.titleLarge)
                    Text(stop.place.name, style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(16.dp))
                    Text("도보 ${stop.walkInM}m · 약 ${(stop.walkInM / 66.7).roundToInt()}분 · ${stop.place.category.label}",
                        color = C.sub, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(48.dp))
                    if (course.mode == CourseMode.DRIVE_AND_HOP) {
                        if (!isLast) {
                            PrimaryButton("다음 장소로 안내", enabled = true) { vm.goToNextStop() }
                            Spacer(Modifier.height(14.dp))
                            Text("둘러본 뒤 다시 출발하면 다음 장소 안내가 자동으로 시작돼요", color = C.sub,
                                style = MaterialTheme.typography.bodyMedium)
                        } else PrimaryButton("코스 마치기", enabled = true) { vm.finish() }
                    } else {
                        PrimaryButton(if (isLast) "코스 마치기" else "다음 장소", enabled = true) { vm.nextWalkStop() }
                    }
                }
                RunPhase.Finished, null -> {
                    Text("코스 완료!", style = MaterialTheme.typography.displaySmall, color = C.accent)
                    course.mainParking?.let {
                        Spacer(Modifier.height(16.dp))
                        Text("차는 ${it.name}에 있어요", style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(Modifier.height(48.dp))
                    PrimaryButton("처음으로", enabled = true) { vm.backToHome() }
                }
            }
            Spacer(Modifier.height(20.dp))
            if (!v.restricted && phase !is RunPhase.Finished) SecondaryButton("코스 그만하기") { vm.backToHome() }
        }
    }
}

@Composable
private fun StepRow(badge: String, title: String, sub: String, done: Boolean, active: Boolean) {
    val color = when { active -> C.accent; done -> C.good; else -> C.sub }
    Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(color.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
            Text(if (done) "✓" else badge, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.width(20.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (done) C.sub else C.text)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = C.sub)
        }
    }
}

// ───────────────────────── 시연 패널 ─────────────────────────

@Composable
private fun DemoPanel(vm: DriveViewModel, v: VehicleState, onClose: () -> Unit) {
    val rain by vm.rainDemo.collectAsState()
    val low by vm.lowEnergyDemo.collectAsState()
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.width(720.dp).clip(RoundedCornerShape(28.dp)).background(C.surface).padding(40.dp)) {
            Text("시연 패널", style = MaterialTheme.typography.headlineMedium)
            Text("발표·심사 시연용 상황 바꾸기", color = C.sub, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))
            if (!v.connected) ToggleRow("주행 중 (기어 D)", v.drive == DriveState.DRIVING) { vm.setDemoDriving(it) }
            else Text("기어는 Pleos 차량 값(Virtual Console)을 따릅니다", color = C.sub, style = MaterialTheme.typography.bodyMedium)
            ToggleRow("비 오는 날", rain) { vm.setDemoRain(it) }
            ToggleRow("배터리 12%", low) { vm.setDemoLowEnergy(it) }
            Spacer(Modifier.height(16.dp))
            SecondaryButton("내비 도착 이벤트 보내기") { vm.simulateArrival(); onClose() }
            Spacer(Modifier.height(12.dp))
            Text(if (vm.usingLiveKeys) "외부 API 키 사용 중" else "API 키 없음 → 데모 데이터", color = C.sub,
                style = MaterialTheme.typography.bodyMedium)
            vm.tmapUsage()?.let {
                Spacer(Modifier.height(8.dp))
                Text("TMAP 사용량(무료 한도 보호): $it", color = if (vm.tmapLimited()) C.busy else C.sub,
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ───────────────────────── 공통 컴포넌트 ─────────────────────────

@Composable
private fun BigOption(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    Row(
        modifier.clip(RoundedCornerShape(24.dp))
            .background(if (selected) C.accentDim else C.surface)
            .border(2.dp, if (selected) C.accent else C.line, RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) C.accent else C.sub, modifier = Modifier.size(44.dp))
        Spacer(Modifier.width(20.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = C.sub)
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(40.dp))
            .background(if (selected) C.accent else C.surface)
            .border(2.dp, if (selected) C.accent else C.line, RoundedCornerShape(40.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 34.dp, vertical = 20.dp),
    ) { Text(label, style = MaterialTheme.typography.titleMedium, color = if (selected) Color.White else C.text) }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, loading: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(28.dp))
            .background(if (enabled) C.accent else C.surface2)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) { CircularProgressIndicator(color = Color.White, modifier = Modifier.size(40.dp)); Spacer(Modifier.width(20.dp)) }
        Text(text, style = MaterialTheme.typography.headlineMedium, color = Color.White)
    }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(28.dp))
            .border(2.dp, C.line, RoundedCornerShape(28.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.titleLarge, color = C.text) }
}

@Composable
private fun IconButtonBig(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(24.dp)).background(C.surface).clickable(onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, label, tint = C.text, modifier = Modifier.size(36.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}
