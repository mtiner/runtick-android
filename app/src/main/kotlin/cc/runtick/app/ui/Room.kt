package cc.runtick.app.ui

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import cc.runtick.app.core.ClockMapping
import cc.runtick.app.core.MonotonicClock
import cc.runtick.app.core.Race
import cc.runtick.app.core.StageDelay
import cc.runtick.app.core.User
import cc.runtick.app.network.APIClient
import cc.runtick.app.services.RoomModel
import kotlinx.coroutines.launch

/** 角色字符串标题（与 core 的 DeviceRole 字符串保持一致）。 */
internal fun roleTitle(role: String?): String = when (role) {
    "start" -> "起点"
    "finish" -> "终点"
    else -> "同步中"
}

/** 竞赛是否进行中（ready / running）。 */
private val Race.isActive: Boolean
    get() = status == "ready" || status == "running"

/** 秒数输入显示：整数不带小数点。 */
private fun formatStageSeconds(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

/** 房间视图：连接状态、校时、角色选择、发令、终点触控与成绩展示。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomView(model: RoomModel, api: APIClient?, user: User, leave: () -> Unit) {
    val scope = rememberCoroutineScope()
    var soundCheck by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var distance by rememberSaveable { mutableStateOf("100") }

    // 订阅 RoomModel 状态流（派生属性在重组时按最新值求值）。
    val members by model.membersFlow.collectAsState()
    val connected by model.connectedFlow.collectAsState()
    val ready by model.readyFlow.collectAsState()
    val raceState by model.raceFlow.collectAsState()
    val clockState by model.clockFlow.collectAsState()
    val syncing by model.syncingFlow.collectAsState()
    val progress by model.progressFlow.collectAsState()
    val busy by model.busyFlow.collectAsState()
    val firing by model.firingFlow.collectAsState()
    val cue by model.cueFlow.collectAsState()
    val pending by model.pendingFlow.collectAsState()
    val submitting by model.submittingFlow.collectAsState()
    val stages by model.stagesFlow.collectAsState()
    // 普通局部变量：让空检查后的智能转换可用。
    val race = raceState
    val clock = clockState
    val role = model.role
    val raceRole = model.raceRole

    // 终点触控全屏条件：raceRole 为终点、race 进行中、未保存终点时间、已校时且无待提交事件。
    val finishVisible = raceRole == "finish" && race != null && race.isActive &&
        race.finishAt == null && clock != null && pending == null

    Box(Modifier.fillMaxSize()) {
        RTPage {
            RTBrand(
                onExit = leave,
                exiting = busy,
                showHelp = true,
                onHelp = { showHelp = true },
                modifier = Modifier.rtEntrance(0)
            )
            RTHero(
                badge = "房间 ${model.code}",
                title = when {
                    race != null && race.status == "completed" -> "本次成绩"
                    race != null && race.isActive -> if (raceRole == "start") "起跑发令" else "终点记录"
                    else -> "房间准备"
                },
                subtitle = "准备好，就出发。",
                modifier = Modifier.rtEntrance(40)
            )
            // 连接与校时卡。
            RTCard(title = "房间 ${model.code}", modifier = Modifier.rtEntrance(80)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        if (connected) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                        contentDescription = null,
                        tint = RT.muted,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        when {
                            connected && ready -> "两端已就绪"
                            connected -> "等待另一台手机"
                            else -> "连接中，自动重连…"
                        },
                        fontSize = 14.sp, color = RT.ink
                    )
                }
                if (syncing) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("HTTP 校时 $progress/20", fontSize = 12.sp, color = RT.muted)
                        LinearProgressIndicator(
                            progress = { progress / 20f },
                            modifier = Modifier.fillMaxWidth(),
                            color = RT.deep,
                            trackColor = RT.aqua.copy(alpha = 0.18f)
                        )
                    }
                } else if (clock != null) {
                    Text(
                        "20 次校时 · 平均 RTT %.1f ms · 估算 ±%.0f ms".format(clock!!.avgRttMs, clock!!.uncertaintyMs),
                        fontSize = 12.sp, lineHeight = 17.sp, color = RT.muted
                    )
                }
                RTButton(
                    onClick = { scope.launch { model.calibrate() } },
                    enabled = !syncing && !firing && !model.pressed
                ) { RTButtonInkText("重新校准时间") }
            }

            // 角色选择与成员卡：仅在无进行中的比赛时显示。
            if (race == null || (!race.isActive && race.status != "completed")) {
                RoleSelectionCard(model, connected, busy)
                RTCard(title = "房间成员（${members.size}/2）", modifier = Modifier.rtEntrance(120)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        members.forEach { member ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(member.loginId, fontSize = 15.sp, color = RT.ink)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "${roleTitle(member.deviceRole)} · ${if (member.online) "在线" else "离线"}",
                                    fontSize = 13.sp, color = RT.muted
                                )
                            }
                        }
                    }
                }
            }

            // 比赛卡片。
            if (race != null) {
                RaceCard(
                    model = model,
                    race = race!!,
                    raceRole = raceRole,
                    distance = distance,
                    onDistanceChange = { distance = it }
                )
            }

            // 起点专属：开始记录入口（弹出声音确认）。
            if (role == "start" && (race == null || (!race.isActive && race.status != "completed"))) {
                RTButton(
                    onClick = { soundCheck = true },
                    secondary = false,
                    enabled = ready && connected && clock != null && !syncing && !busy
                ) { RTButtonText("开始记录") }
            }

            // 待提交时间戳。
            pending?.let { current ->
                RTCard(title = "待提交时间戳") {
                    Text(
                        "轮次 ${current.raceId} · ${roleTitle(current.event.kind)}\n原始时间戳已保留，联网后自动重试",
                        fontSize = 14.sp, lineHeight = 20.sp, color = RT.ink
                    )
                    RTButton(
                        onClick = { scope.launch { model.submit() } },
                        enabled = !submitting
                    ) { RTButtonInkText("重试提交") }
                }
            }

            // 历史成绩入口（房间内带 room 过滤）。
            if (api != null) {
                RTButton(onClick = { showHistory = true }) {
                    RTButtonInkText("历史成绩")
                }
            }
            RTButton(onClick = leave) { RTButtonInkText("退出房间") }
        }

        // 声音确认弹层。
        if (soundCheck) {
            ModalBottomSheet(
                onDismissRequest = { soundCheck = false },
                containerColor = RT.background
            ) {
                RTPage {
                    RTCard(title = "开始前确认声音") {
                        Text(
                            "调高音量，使用手机扬声器；确认终点已进入触控区后再发令。",
                            fontSize = 14.sp, lineHeight = 20.sp, color = RT.ink
                        )
                        AudioPreparationCard()
                        RTButton(
                            onClick = {
                                soundCheck = false
                                scope.launch { model.raceAction() }
                            },
                            secondary = false
                        ) { RTButtonText("已确认声音，开始记录") }
                    }
                    RTButton(onClick = { soundCheck = false }) { RTButtonInkText("返回准备") }
                }
            }
        }

        // 终点触控全屏。
        if (finishVisible) {
            FinishPad(model = model, loginId = user.loginId, leave = leave)
        }

        // 房间内历史成绩全屏页。
        if (showHistory && api != null) {
            HistoryView(api = api, user = user, room = model.code, onBack = { showHistory = false })
        }

        if (showHelp) RTGlobalHelpSheet(onDismiss = { showHelp = false })
    }

    // 触控全屏或声音确认时抑制全局弹窗，错误由各自界面处理。
    RTErrorAlert(model.errorFlow.collectAsState().value, enabled = !finishVisible && !soundCheck)
}

/** 角色选择卡：胶囊分段控件 + 滑块动画。 */
@Composable
private fun RoleSelectionCard(model: RoomModel, connected: Boolean, busy: Boolean) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }

    RTCard(title = "我的角色", modifier = Modifier.rtEntrance(80)) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .background(RT.ink.copy(alpha = 0.055f), RoundedCornerShape(50))
                .padding(4.dp)
        ) {
            val itemWidth = maxWidth / 2
            val highlightX by animateDpAsState(
                targetValue = if (model.role == "start") 0.dp else itemWidth,
                animationSpec = spring(dampingRatio = 0.85f, stiffness = 400f),
                label = "roleHighlight"
            )
            // 滑块：白色胶囊随选中角色平移。
            Box(
                Modifier
                    .offset(x = highlightX)
                    .width(itemWidth)
                    .height(46.dp)
                    .background(Color.White, RoundedCornerShape(50))
            )
            Row(Modifier.fillMaxWidth()) {
                listOf("start" to Icons.Filled.DirectionsRun, "finish" to Icons.Filled.Flag).forEach { (value, icon) ->
                    val selected = model.role == value
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clickable(
                                enabled = connected && !busy && !saving,
                                onClick = {
                                    scope.launch {
                                        saving = true
                                        try { model.pick(value) } finally { saving = false }
                                    }
                                }
                            ),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (saving && selected) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = RT.deep
                            )
                        } else {
                            Icon(icon, contentDescription = null, tint = RT.ink, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.size(8.dp))
                        Text(
                            roleTitle(value),
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = if (selected) RT.ink else RT.muted
                        )
                    }
                }
            }
        }
        if (saving) Text("正在同步角色…", fontSize = 12.sp, color = RT.muted)
    }
}

/** 比赛卡片：按状态展示发令设置、终点提示或成绩。 */
@Composable
private fun RaceCard(
    model: RoomModel,
    race: Race,
    raceRole: String?,
    distance: String,
    onDistanceChange: (String) -> Unit
) {
    val statusTitle = mapOf(
        "ready" to "准备中", "running" to "计时中", "completed" to "已完成",
        "invalid" to "无效", "cancelled" to "已取消"
    )[race.status] ?: race.status

    RTCard(title = "本轮 · $statusTitle") {
        if (race.reactionStatus != null) Text(cc.runtick.app.core.reactionText(race.reactionMs, race.reactionStatus), color = RT.ink)
        when {
            race.status == "completed" -> CompletedSection(model, race, raceRole, distance, onDistanceChange)
            race.isActive -> {
                if (raceRole == "start") {
                    StartSection(model, race)
                } else {
                    Text(
                        if (race.finishAt == null) "校时完成后进入终点触控区" else "终点时间已保存，等待枪声时间戳",
                        fontSize = 16.sp, color = RT.ink
                    )
                }
            }
            race.status == "invalid" -> Text(
                "终点早于枪声或用时超过一小时，本轮无效，请重新开始。",
                fontSize = 14.sp, lineHeight = 20.sp, color = RT.ink
            )
        }
    }
}

/** 已完成：大数字成绩 + PB 庆祝 / 距离保存。 */
@Composable
private fun CompletedSection(
    model: RoomModel,
    race: Race,
    raceRole: String?,
    distance: String,
    onDistanceChange: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val busy by model.busyFlow.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RTChip(text = "计时完成", lemon = true)
            // 大数字：宽度不足时降级字号。
            BoxWithConstraints {
                val size = if (maxWidth < 300.dp) 42.sp else 62.sp
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        String.format("%.3f", (race.elapsedMs ?: 0.0) / 1000.0),
                        fontSize = size, fontWeight = FontWeight.Bold, color = RT.ink
                    )
                    Text("秒", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = RT.muted, modifier = Modifier.padding(bottom = 10.dp))
                }
            }
        }

        Text(
            "服务端已保存 · 估算误差 ±%.0f ms（不含人工反应）".format(race.uncertaintyMs ?: 0.0),
            fontSize = 12.sp, lineHeight = 17.sp, color = RT.muted
        )
        val saved = race.savedResult
        if (saved != null) {
            // PB：庆祝动画 + 震动。
            LaunchedEffect(saved.isPb) {
                if (saved.isPb) {
                    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    if (vibrator != null) {
                        vibrator.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
                    }
                }
            }
            if (saved.isPb) RTPBCelebration(improvementMs = saved.pbImprovementMs)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (saved.isPb) "🏆" else "✓",
                    fontSize = 14.sp,
                    color = if (saved.isPb) Color(0xFFFF8C00) else RT.deep
                )
                Text(
                    "已保存 ${saved.distance} 米${if (saved.isPb) " · PB！" else ""}",
                    fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    color = if (saved.isPb) Color(0xFFFF8C00) else RT.deep
                )
            }
            saved.pbImprovementMs?.let {
                Text("比原 PB 快 %.2f 秒".format(it / 1000.0), fontSize = 14.sp, color = RT.ink)
            }
            RTButton(
                onClick = { scope.launch { model.raceAction("next") } },
                secondary = false,
                enabled = !busy
            ) { RTButtonText("下一组") }
        } else if (raceRole == "finish") {
            Text("成绩保存至起点运动员名下", fontSize = 14.sp, color = RT.ink)
            RTTextField(
                value = distance,
                onValueChange = { value -> onDistanceChange(value.filter { it.isDigit() }.take(6)) },
                placeholder = "距离（米）",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            // 距离快捷 chips。
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(20, 30, 40, 50, 60, 80, 100, 120, 150, 200, 300, 400, 800, 1500).forEach { value ->
                    Text(
                        "$value",
                        modifier = Modifier
                            .border(1.dp, RT.ink.copy(alpha = 0.12f), RoundedCornerShape(50))
                            .clickable { onDistanceChange(value.toString()) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        fontSize = 14.sp, color = RT.ink
                    )
                }
            }
            RTButton(
                onClick = { scope.launch { model.save(distance.toIntOrNull() ?: 0) } },
                secondary = false,
                enabled = !busy
            ) { RTButtonText("保存成绩") }
        } else {
            Text("等待终点记录员填写距离并保存", fontSize = 14.sp, color = RT.ink)
        }
    }
}

/** 起点发令：提示语 + 三段等待设置 + 开始发令。 */
@Composable
private fun StartSection(model: RoomModel, race: Race) {
    val scope = rememberCoroutineScope()
    val syncing by model.syncingFlow.collectAsState()
    val firing by model.firingFlow.collectAsState()
    val pending by model.pendingFlow.collectAsState()
    val clock by model.clockFlow.collectAsState()
    val stages by model.stagesFlow.collectAsState()
    val cue by model.cueFlow.collectAsState()
    val editingDisabled = firing || race.startAt != null || pending != null

    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            cue?.takeIf { it.isNotEmpty() } ?: "等待发令",
            fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = RT.ink
        )
        StageSettings(stages = stages, disabled = editingDisabled, onChange = { model.stages = it })
        Text("手机贴靠或固定在起跑器上；反应时为设备运动检测估算。", fontSize = 12.sp, color = RT.muted)
        RTButton(
            onClick = { scope.launch { model.fire() } },
            secondary = false,
            enabled = clock != null && !syncing && !firing &&
                race.startAt == null && pending == null && stages.all { it.valid }
        ) { RTButtonText("开始发令") }
    }
}

/** 更新第 index 段设置（stages setter 会同步持久化）。 */
private fun updateStage(model: RoomModel, index: Int, transform: (StageDelay) -> StageDelay) {
    val list = model.stages.toMutableList()
    list[index] = transform(list[index])
    model.stages = list
}

/**
 * 终点触控全屏：按下变色、中央圆环脉冲、松手记录时间戳。
 * 切后台或离开页面时取消触控，不产生成绩。
 */
@Composable
private fun FinishPad(model: RoomModel, loginId: String, leave: () -> Unit) {
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReduceMotion()
    val lifecycleOwner = LocalLifecycleOwner.current
    val pressed by model.pressedFlow.collectAsState()
    val clock by model.clockFlow.collectAsState()
    val race by model.raceFlow.collectAsState()

    // 禁止返回键关闭；返回键仅取消触控。
    BackHandler(enabled = true) { model.cancelPress() }

    // 切后台取消触控（不产生成绩）；离开页面同样取消。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) model.cancelPress()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            model.cancelPress()
        }
    }

    val background = if (pressed) Color(0xFFB8ED78) else Color(0xFF66DBD3)
    val content = Color(0xFF123D3B)

    Box(
        Modifier
            .fillMaxSize()
            .background(background)
            // 全屏触控捕获：按下 press、松手 release（单调钟时间戳）、指针离开 cancel。
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Press -> model.press()
                            PointerEventType.Release -> model.release(MonotonicClock.now())
                            PointerEventType.Exit -> if (model.pressed) model.cancelPress()
                        }
                    }
                }
            }
    ) {
        Column(
            Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("$loginId · 房间 ${model.code}", fontSize = 14.sp, color = content)
                Spacer(Modifier.weight(1f))
                Text(
                    "返回房间",
                    modifier = Modifier
                        .border(1.dp, content.copy(alpha = 0.35f), RoundedCornerShape(50))
                        .clickable { model.cancelPress(); leave() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = content
                )
            }
            Spacer(Modifier.weight(1f))
            // 中央圆环 + 手指图标脉冲。
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(180.dp).border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape))
                val ringSize by animateDpAsState(
                    targetValue = if (pressed) 130.dp else 110.dp,
                    animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow),
                    label = "ring"
                )
                Box(Modifier.size(ringSize).border(2.dp, Color.White.copy(alpha = 0.45f), CircleShape))
                val pulse = rememberInfiniteTransition(label = "pulse")
                val pulseAlpha by pulse.animateFloat(
                    initialValue = 0.55f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
                    label = "pulseAlpha"
                )
                Icon(
                    Icons.Filled.TouchApp,
                    contentDescription = null,
                    tint = content.copy(alpha = if (pressed && !reduceMotion) pulseAlpha else 1f),
                    modifier = Modifier.size(64.dp)
                )
            }
            Spacer(Modifier.height(30.dp))
            Text(
                if (pressed) "冲线时松手" else "按住屏幕，等待冲线",
                fontSize = 30.sp, fontWeight = FontWeight.Bold, color = content,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (race?.startAt == null) "可以提前按住 · 等待枪声" else "计时中 · 松开记录",
                fontSize = 15.sp, color = content.copy(alpha = 0.85f)
            )
            Spacer(Modifier.height(6.dp))
            Text("取消触控不会记录成绩", fontSize = 12.sp, color = content.copy(alpha = 0.6f))
            Spacer(Modifier.weight(1f))
            if (clock != null && !clock!!.fresh) {
                Text(
                    "重新校时",
                    modifier = Modifier
                        .border(1.dp, content.copy(alpha = 0.35f), RoundedCornerShape(50))
                        .clickable { scope.launch { model.calibrate() } }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    fontSize = 14.sp, color = content
                )
            }
        }
    }

    // FinishPad 自己的错误弹窗。
    RTErrorAlert(model.errorFlow.collectAsState().value, enabled = true)
}
