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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
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
import kotlinx.coroutines.delay
import kotlin.math.ceil
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

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
fun RoomView(model: RoomModel, api: APIClient?, user: User, leave: () -> Unit, onGuide: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var soundCheck by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    fun requestExit() { if (model.race?.active == true || model.pending != null) confirmExit = true else leave() }
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
    val roleRevision by model.roleRevisionFlow.collectAsState()
    val race = raceState
    val clock = clockState
    val role = roleRevision.let { model.role }
    val raceRole = model.raceRole

    // 终点触控全屏条件：raceRole 为终点、race 进行中、未保存终点时间、已校时且无待提交事件。
    val finishVisible = raceRole == "finish" && race != null && race.isActive &&
        race.finishAt == null && model.hasRecordingClock && pending == null

    var dismissedRecording by rememberSaveable { mutableStateOf<String?>(null) }
    val startVisible = raceRole == "start" && race?.isActive == true && dismissedRecording != race.id
    val showFinish = finishVisible && dismissedRecording != race?.id
    fun dismissRecording() { model.interrupt(); dismissedRecording = race?.id }
    LaunchedEffect(startVisible, race?.id, race?.starterSettings) {
        if (race?.isActive == true) soundCheck = false
        if (startVisible) model.autoFire()
    }

    Box(Modifier.fillMaxSize()) {
        RTPage {
            RTBrand(
                onExit = { requestExit() },
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
            if (race?.isActive != true) RTCard(title = "房间 ${model.code}", modifier = Modifier.rtEntrance(80)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        if (connected) Icons.Filled.Language else Icons.Filled.CloudOff,
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
                        TimeSyncStatus(model)
                        LinearProgressIndicator(
                            progress = { progress.toFloat() / model.syncTarget },
                            modifier = Modifier.fillMaxWidth(),
                            color = RT.deep,
                            trackColor = RT.aqua.copy(alpha = 0.18f)
                        )
                    }
                } else { TimeSyncStatus(model) }
                RTButton(
                    onClick = { scope.launch { model.calibrate(force = true) } },
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
                                UserAvatar(member.avatar,member.loginId,36.dp)
                                Spacer(Modifier.size(8.dp))
                                Text(member.loginId, modifier = Modifier.weight(1f), fontSize = 15.sp, color = RT.ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Box(Modifier.size(38.dp).shadow(if(member.online) 6.dp else 0.dp,CircleShape,ambientColor=RT.aqua,spotColor=RT.aqua)
                                    .background((if(member.online) RT.aqua else Color.Gray).copy(alpha=.18f),CircleShape)
                                    .border(1.dp,if(member.online) RT.deep else Color.Gray,CircleShape)
                                    .semantics {contentDescription="${roleTitle(model.displayedRole(member))}，${if(member.online) "在线" else "离线"}"},contentAlignment=Alignment.Center) {
                                    Icon(if(model.displayedRole(member)=="start") Icons.Filled.DirectionsRun else Icons.Filled.Flag,contentDescription=null,tint=if(member.online) RT.deep else Color.Gray)
                                    if(!member.online) Icon(Icons.Filled.CloudOff,contentDescription=null,modifier=Modifier.size(12.dp).align(Alignment.BottomEnd),tint=Color.Gray)
                                }
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

            // 任一端确认设置后进入同一轮记录。
            if (role != null && (race == null || (!race.isActive && race.status != "completed"))) {
                RTButton(
                    onClick = { scope.launch { model.beginRecording() } },
                    secondary = false,
                    enabled = ready && connected && clock != null && !syncing && !busy
                ) { RTButtonText("开始记录") }
            }

            if ((raceRole == "start" || finishVisible) && race?.isActive == true && dismissedRecording == race.id) {
                RTButton(onClick = { dismissedRecording = null }) { RTButtonInkText("进入记录页") }
            }

            if(race?.isActive!=true) RTButton(onClick={model.editingSettings=true;soundCheck=true}) { RTButtonInkText("房间发令设置") }
            // 待提交时间戳。
            pending?.let { current ->
                RTCard(title = "计时记录待同步") {
                    Text(
                        "计时记录已保留在此设备上，联网后会自动重试同步。",
                        fontSize = 14.sp, lineHeight = 20.sp, color = RT.ink
                    )
                    RTButton(
                        onClick = { scope.launch { model.submit() } },
                        enabled = !submitting
                    ) { RTButtonInkText("重新同步") }
                }
            }

            // 历史成绩入口（房间内带 room 过滤）。
            if (api != null) {
                RTButton(onClick = { showHistory = true }) {
                    RTButtonInkText("历史成绩")
                }
            }
            RTButton(onClick = { requestExit() }) { RTButtonInkText("退出房间") }
        }

        // 声音确认弹层。
        if (soundCheck) {
            ModalBottomSheet(
                onDismissRequest = { soundCheck = false; model.editingSettings=false },
                containerColor = RT.background
            ) {
                RTPage {
                    RTCard(title = "发令设置") {
                        Text(
                            "设置将用于房间内后续每轮训练。请调高媒体音量，使用手机扬声器。",
                            fontSize = 14.sp, lineHeight = 20.sp, color = RT.ink
                        )
                        StageSettings(stages = stages, allowSetToggle = true, onChange = { model.stages = it })
                    }
                    Column(Modifier.fillMaxWidth().background(RT.background).padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        RTButton(onClick = {
                            scope.launch { try { model.saveRoomSettings(); model.editingSettings=false; soundCheck=false } catch(e: Exception) { model.error=e.message ?: "保存设置失败" } }
                        }, secondary = false) { RTButtonText("保存设置") }
                    RTButton(onClick = { soundCheck = false; model.editingSettings=false }) { RTButtonInkText("返回准备") }
                    }
                }
            }
        }

        // 终点触控全屏。
        if (showFinish || startVisible) {
            Dialog(onDismissRequest = { dismissRecording() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                if (showFinish) FinishPad(model = model, loginId = user.loginId, leave = { dismissRecording() })
                else StarterPad(model = model, onBack = { dismissRecording() })
            }
        }

        // 房间内历史成绩全屏页。
        if (showHistory && api != null) {
            HistoryView(api = api, user = user, room = model.code, onBack = { showHistory = false })
        }

        if (showHelp) RTGlobalHelpSheet(onDismiss = { showHelp = false }, onGuide = onGuide)
        if (confirmExit) androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("退出当前房间？") },
            text = { Text("当前训练将中断。未保存的成绩可能无法再保存；待同步记录会继续保留在此设备上。") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmExit = false; leave() }) { Text("退出房间") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmExit = false }) { Text("继续训练") } }
        )
    }

    // 触控全屏或声音确认时抑制全局弹窗，错误由各自界面处理。
    RTErrorAlert(model.errorFlow.collectAsState().value, enabled = !showFinish && !startVisible && !soundCheck)
}

/** 角色选择卡：胶囊分段控件 + 滑块动画。 */
@Composable
private fun RoleSelectionCard(model: RoomModel, connected: Boolean, busy: Boolean) {
    val scope = rememberCoroutineScope()
    val revision by model.roleRevisionFlow.collectAsState()
    val displayedRole = revision.let { model.role }

    RTCard(title = "我的角色", modifier = Modifier.rtEntrance(80)) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .background(RT.ink.copy(alpha = 0.055f), RoundedCornerShape(50))
                .padding(4.dp)
        ) {
            val itemWidth = maxWidth / 2
            val highlightX by animateDpAsState(
                targetValue = if (displayedRole == "start") 0.dp else itemWidth,
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
                    val selected = displayedRole == value
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clickable(
                                enabled = connected && !busy,
                                onClick = {
                                    model.pick(value)
                                }
                            ),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(icon, contentDescription = null, tint = RT.ink, modifier = Modifier.size(16.dp))
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
        if (race.reactionStatus == "false_start") RTChip("抢跑", lemon = true)
        if (race.reactionStatus != null) Text(cc.runtick.app.core.reactionText(race.reactionMs, race.reactionStatus), color = RT.ink)
        when {
            race.status == "completed" -> CompletedSection(model, race, raceRole, distance, onDistanceChange)
            race.isActive -> {
                if (raceRole == "start") {
                    StartSection(model, race)
                } else {
                    Text(
                        if (race.finishAt == null) "校时完成后进入终点触控区" else "已记录冲线，等待起点同步",
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
                val value = String.format("%.3f", (race.elapsedMs ?: 0.0) / 1000.0)
                val fontScale = LocalDensity.current.fontScale
                val fitted = ((maxWidth.value - 36f * fontScale) / (value.length * 0.65f)).coerceIn(18f, 62f)
                val size = (fitted / fontScale).sp
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        value,
                        fontSize = size, fontWeight = FontWeight.Bold, color = RT.ink, maxLines = 1, softWrap = false
                    )
                    Text("秒", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = RT.muted, modifier = Modifier.padding(bottom = 10.dp))
                }
            }
        }

        Text(
            "本轮计时完成 · 估算误差 ±%.0f ms（不含人工反应）".format(race.uncertaintyMs ?: 0.0),
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
        if(saved==null) RTButton(onClick={scope.launch {model.raceAction("discard")}},enabled=!busy,secondary=true) { RTButtonInkText("放弃保存本轮成绩") }
    }
}

/** 起点发令：提示语 + 三段等待设置 + 开始发令。 */
@Composable
private fun StartSection(model: RoomModel, race: Race) {
    Text("本轮已进入记录流程，切换页面或返回会中断发令。", color = RT.muted)

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

    BackHandler { model.cancelPress(); leave() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) model.cancelPress() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); model.cancelPress() }
    }
    val background = if (pressed) Color(0xFFB8ED78) else Color(0xFF66DBD3)
    val content = Color(0xFF123D3B)
    Column(Modifier.fillMaxSize().background(background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        RecordingHeader("$loginId · 房间 ${model.code}", onBack = { model.cancelPress(); leave() })
        var stage by remember { mutableStateOf(model.stageText()) }
        LaunchedEffect(model) { while(true) { stage=model.stageText(); delay(50) } }
        Box(Modifier.weight(1f).fillMaxWidth().background(RT.aqua.copy(alpha=0.2f)),contentAlignment=Alignment.Center) {
            Text(stage,fontSize=36.sp,fontWeight=FontWeight.Bold,color=RT.ink)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val availableHeight = maxHeight.value
            val ringSize = (availableHeight * 0.25f).coerceIn(80f, 180f).dp
            val density = LocalDensity.current
            Column(
                Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)
                    .pointerInput(model) {
                        awaitEachGesture {
                            val first = awaitFirstDown(requireUnconsumed = true)
                            val pointer = first.id
                            model.press()
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == pointer }
                                    if (change == null || change.isConsumed || event.changes.count { it.pressed } > 1 ||
                                        change.position.x !in 0f..size.width.toFloat() || change.position.y !in 0f..size.height.toFloat()) break
                                    if (!change.pressed) {
                                        val at = MonotonicClock.now() + change.uptimeMillis - android.os.SystemClock.uptimeMillis()
                                        model.release(at)
                                        break
                                    }
                                }
                            } finally { model.cancelPress() }
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.weight(1f))
                Box(contentAlignment = Alignment.Center) {
                    Box(Modifier.size(ringSize).border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape))
                    val inner by animateDpAsState(if (pressed) ringSize * 0.75f else ringSize * 0.62f, label = "ring")
                    Box(Modifier.size(inner).border(2.dp, Color.White.copy(alpha = 0.45f), CircleShape))
                    Icon(Icons.Filled.TouchApp, contentDescription = null, tint = content, modifier = Modifier.size(ringSize * 0.36f))
                }
                Spacer(Modifier.height((availableHeight * 0.035f).coerceAtMost(24f).dp))
                Text(if (pressed) "冲线时松手" else "按住屏幕，等待冲线",
                    fontSize = (availableHeight * 0.055f / density.fontScale).coerceIn(16f, 30f).sp,
                    fontWeight = FontWeight.Bold, color = content, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(if (race?.startAt == null) "可以提前按住 · 等待枪声" else "计时中 · 松开记录", fontSize = 15.sp, color = content, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text("取消触控不会记录成绩", fontSize = 12.sp, color = content, textAlign = TextAlign.Center)
                Spacer(Modifier.weight(1f))
            }
        }
        if (!model.hasRecordingClock) RTButton(onClick = { scope.launch { model.calibrate(force = true) } }, modifier = Modifier.padding(16.dp)) { RTButtonInkText("重新校时") }
    }

    // FinishPad 自己的错误弹窗。
    RTErrorAlert(model.errorFlow.collectAsState().value, enabled = true)
}

/** Full-screen starter presentation follows the same audio timeline as capture. */
@Composable
private fun StarterPad(model: RoomModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val firing by model.firingFlow.collectAsState()
    val syncing by model.syncingFlow.collectAsState()
    val pending by model.pendingFlow.collectAsState()
    val race by model.raceFlow.collectAsState()
    val cue by model.cueFlow.collectAsState()
    val deadline by model.cueDeadlineFlow.collectAsState()
    var now by remember { mutableStateOf(MonotonicClock.now()) }
    val lifecycle = LocalLifecycleOwner.current
    BackHandler(onBack = onBack)
    LaunchedEffect(deadline) { while (deadline != null) { now = MonotonicClock.now(); delay(100) } }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) onBack() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); model.interrupt() }
    }
    Column(Modifier.fillMaxSize().rtBackdrop().windowInsetsPadding(WindowInsets.safeDrawing)) {
        RecordingHeader("房间 ${model.code}", onBack)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Spacer(Modifier.weight(1f))
                Text(cue ?: "准备发令", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = RT.ink, textAlign = TextAlign.Center)
                deadline?.let { Text(ceil(((it - now) / 1000).coerceAtLeast(0.0)).toInt().toString(), fontSize = 96.sp, fontWeight = FontWeight.Bold, color = RT.ink) }
                Text(if (race?.startAt == null) "跟随口令，准备出发" else "枪声已发出 · 等待冲线", color = RT.muted, textAlign = TextAlign.Center)
                if (!firing && race?.startAt == null && pending == null) {
                    RTButton(onClick = { scope.launch { model.fire() } }, enabled = !syncing) { RTButtonInkText("重新发令") }
                }
                Spacer(Modifier.weight(1f))
                Text("保持 App 在前台，使用本机扬声器播放", fontSize = 12.sp, color = RT.muted, textAlign = TextAlign.Center)
            }
        }
    }
    RTErrorAlert(model.errorFlow.collectAsState().value, enabled = true)
}

/** 顶部栏独立于滚动内容及触控区；导航文字适度限幅，正文保留系统字号。 */
@Composable
private fun RecordingHeader(title: String, onBack: () -> Unit) {
    val density = LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(1.5f))) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, Modifier.weight(1f).testTag("recording.roomLabel"), color = RT.ink, fontSize = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            androidx.compose.material3.TextButton(onClick = onBack, modifier = Modifier.testTag("recording.return")) { Text("返回房间", color = RT.ink, fontSize = 14.sp) }
        }
    }
}

@Composable
private fun TimeSyncStatus(model: RoomModel) {
    var text by remember { mutableStateOf(model.syncSummary) }
    LaunchedEffect(model) { while(true) { text=model.syncSummary; delay(1000) } }
    val metrics by model.syncMetricsFlow.collectAsState()
    val syncing by model.syncingFlow.collectAsState()
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(text,fontSize=12.sp,color=RT.muted)
        if(!syncing && model.syncError.isNotEmpty()) Text("校时暂未完成，可继续调整设置。请检查网络后点击重新校准；校时有效后才能开始新一轮。",fontSize=12.sp,color=RT.muted,modifier=Modifier.testTag("room.syncNotice"))
        if(syncing) Text("此次 RTT ${metrics.first?.let { "%.1f ms".format(it) } ?: "—"} · 平均 RTT ${metrics.second?.let { "%.1f ms".format(it) } ?: "—"}",fontSize=12.sp,color=RT.muted)
        else (metrics.second ?: model.clock?.avgRttMs)?.let { Text("平均 RTT %.1f ms".format(it),fontSize=12.sp,color=RT.muted) }
    }
}
