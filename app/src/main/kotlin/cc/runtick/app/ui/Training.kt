package cc.runtick.app.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import cc.runtick.app.core.*
import cc.runtick.app.network.APIClient
import cc.runtick.app.services.*
import kotlinx.coroutines.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.util.Date
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun StageSettings(stages: List<StageDelay>, disabled: Boolean = false, allowSetToggle: Boolean = false, onChange: (List<StageDelay>) -> Unit) {
    fun update(index: Int, stage: StageDelay) { onChange(stages.toMutableList().also { it[index] = stage.normalized() }) }
    Row {
        Text("检测起跑反应时", modifier = Modifier.weight(1f), color = RT.ink)
        Switch(checked = stages[0].reactionEnabled, onCheckedChange = { update(0, stages[0].copy(reactionEnabled = it)) }, enabled = !disabled)
    }
    Text("仅检测枪声后的手机移动；各就各位和预备期间可以移动。检测失败不影响正式计时。", fontSize = 12.sp, color = RT.muted)
    if(allowSetToggle) Row {
        Text("播放预备口令",modifier=Modifier.weight(1f),color=RT.ink)
        Switch(checked=stages[1].enabled,onCheckedChange={update(1,stages[1].copy(enabled=it))},enabled=!disabled)
    }
    stages.forEachIndexed { index, stage ->
        if (index!=1 || stage.enabled) {
            val title = listOf("开始 → 各就位", if (stages[0].enabled) "各就位结束 → 预备" else "开始 → 预备", if(stages[1].enabled) "预备结束 → 枪声" else if(stages[0].enabled) "各就位结束 → 枪声" else "开始 → 枪声")[index]
            Text(title, color = RT.ink)
            Row { Text("随机等待", modifier = Modifier.weight(1f), color = RT.muted)
                Switch(checked = stage.random, onCheckedChange = { update(index, stage.copy(random = it, max = maxOf(stage.min, stage.max))) }, enabled = !disabled) }
            Text(if (stage.random) "${stage.min.toInt()}–${stage.max.toInt()} 秒" else "${stage.min.toInt()} 秒", color = RT.ink)
            if (stage.random) RangeSlider(value = stage.min.toFloat()..stage.max.toFloat(), onValueChange = { update(index, stage.copy(min = it.start.toDouble(), max = it.endInclusive.toDouble())) }, valueRange = 0f..10f, steps = 9, enabled = !disabled)
            else Slider(value = stage.min.toFloat(), onValueChange = { update(index, stage.copy(min = it.toDouble(), max = maxOf(stage.max, it.toDouble()))) }, valueRange = 0f..10f, steps = 9, enabled = !disabled)
        }
    }
}

@Composable fun SoloStartView(api: APIClient, user: User) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stages by remember(user.id) { mutableStateOf(StageDelay.defaults()) }
    var confirmCue by remember { mutableStateOf(false) }; var loadingSettings by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }; var cue by remember { mutableStateOf("") }
    var generation by remember { mutableStateOf(UUID.randomUUID()) }
    var deadline by remember { mutableStateOf<Double?>(null) }
    var now by remember { mutableStateOf(MonotonicClock.now()) }
    var volumeWarning by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ReactionResult?>(null) }; var error by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    if (showHistory) { SoloHistoryView(api, onBack = { showHistory = false }); return }
    var task by remember { mutableStateOf<Job?>(null) }
    val audio = remember { StarterAudio(context) }; val motion = remember { ReactionMotion(context) }
    fun stop() { generation = UUID.randomUUID(); task?.cancel(); audio.stop(); motion.end(); running = false; deadline = null; volumeWarning = false }
    fun start(allowReducedVolume: Boolean = false) {
        if (running || stages.size != 3 || stages.any { !it.valid }) return
        if (!allowReducedVolume && AudioReadiness.volumePercent(context) < 100) { volumeWarning = true; return }
        volumeWarning = false
        running = true; result = null; error = ""; cue = "请保持手机与起跑器稳定…"
        val saved = stages.map { it.copy() }; val id = UUID.randomUUID().toString()
        val token = UUID.randomUUID(); generation = token
        task = scope.launch {
            try {
                try { motion.prepare(saved[0].reactionEnabled) } catch (e: CancellationException) { throw e } catch (_: Exception) { motion.stop() }
                ensureActive()
                if (generation != token) return@launch
                val (at, _) = audio.fire(MonotonicClock.now(), saved,
                    onCue = { if (generation == token) cue = it },
                    onDeadline = { if (generation == token) deadline = it })
                if (generation != token) return@launch
                val firedAt = at
                motion.gun(at) { value ->
                    if (generation == token) result = value
                    val body = buildJsonObject { put("id", id); put("firedAt", firedAt); put("athleteId", user.id); put("settings", AppJson.encodeToJsonElement(ListSerializer(StageDelay.serializer()), saved)); put("reactionMs", value.reactionMs?.let { JsonPrimitive(it) } ?: JsonNull); put("reactionStatus", value.reactionStatus) }
                    TrainingSync.enqueue(context, api, user.id, "/api/starts", body, id)
                    scope.launch {
                        try {
                            TrainingSync.send(context, api, user.id, "/api/starts", body, id)
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { error = "记录尚未同步，已保留在此设备上。请联网后重试。" }
                    }
                }
            } catch (_: CancellationException) {}
            catch (_: Exception) { if (generation == token) { error = "发令未完成，请检查声音输出后重试。"; stop() } }
        }
    }
    LaunchedEffect(deadline) { while (deadline != null) { now = MonotonicClock.now(); delay(100) } }
    val view = LocalView.current
    DisposableEffect(running) { val previous = view.keepScreenOn; if (running) view.keepScreenOn = true; onDispose { view.keepScreenOn = previous } }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stop() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { stop(); lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(user.id) { TrainingSync.retry(context, api, user.id) }
    RTPage {
        RTBrand(); RTHero(badge = "单人训练", title = "起跑发令", subtitle = "为每一次认真训练而生")
        RTCard(title = if (running) "正在发令" else "发令设置") {
            if (!running) {
                Text("秒数使用“我的—起跑设置”。枪声前可调整手机，检测失败不影响发令。", color = RT.muted)
                Text("手机贴靠或固定在起跑器上并保持稳定。反应时为设备运动估算，非专业起跑器计时；小于 100 ms 标注抢跑。", fontSize = 12.sp, color = RT.muted)
                AudioPreparationCard()
                RTButton(onClick = { scope.launch {
                    loadingSettings = true
                    try { stages = loadStarterSettings(api); confirmCue = true }
                    catch(e: Exception) { error = e.message ?: "起跑设置读取失败" }
                    finally { loadingSettings = false }
                } }, secondary = false, enabled = !loadingSettings) { RTButtonText("开始发令") }
            } else {
                Text(cue, fontSize = 34.sp, color = RT.ink)
                deadline?.let { Text(kotlin.math.ceil(((it - now) / 1000).coerceAtLeast(0.0)).toInt().toString(), fontSize = 86.sp, color = RT.ink) }
                result?.let { if (it.reactionStatus == "false_start") RTChip("抢跑", lemon = true); Text(reactionText(it.reactionMs, it.reactionStatus), color = RT.ink) }
                RTButton(onClick = { stop() }) { RTButtonInkText("结束本轮") }
            }
        }
        RTButton(onClick = { showHistory = true }) { RTButtonInkText("单人起跑记录") }
        RTButton(onClick = { scope.launch { TrainingSync.retry(context, api, user.id) } }) { RTButtonInkText("重试同步本机记录") }
    }
    if (confirmCue) AlertDialog(
        onDismissRequest = { confirmCue = false }, title = { Text("本轮播放“各就各位”？") },
        confirmButton = { TextButton(onClick = { confirmCue = false; stages = stages.toMutableList().also { it[0] = it[0].copy(enabled = true) }; start() }) { Text("播放并开始") } },
        dismissButton = { TextButton(onClick = { confirmCue = false; stages = stages.toMutableList().also { it[0] = it[0].copy(enabled = false) }; start() }) { Text("跳过并开始") } }
    )
    if (volumeWarning) AlertDialog(
        onDismissRequest = { volumeWarning = false },
        title = { Text("媒体音量未调至最大") },
        text = { Text("建议调高媒体音量，以便听到口令和枪声。也可以按当前音量继续。") },
        confirmButton = { TextButton(onClick = { start(allowReducedVolume = true) }) { Text("仍然开始") } },
        dismissButton = { TextButton(onClick = { volumeWarning = false }) { Text("先调整音量") } }
    )
    RTErrorAlert(error, enabled = !volumeWarning)
}

@Composable fun AboutView(onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    RTPage {
        RTBrand(); RTHero(badge = "关于", title = "RunTick · 燃刻", subtitle = "为每一次认真训练而生")
        RTCard(title = "让每一次出发，有迹可循") {
            Text("双端短跑计时、单人原声发令、起跑反应检测与训练成绩管理。", color = RT.ink)
            Text("反应时如何测量", color = RT.ink)
            Text("将起点手机贴靠或固定在起跑器上并保持稳定，使用加速度计、陀螺仪与本机音频时钟计算枪声到检测到手机移动的时间差。", color = RT.ink)
            Text("小于 100 ms 标注抢跑；这是设备运动检测估算，受安装方式、采样率、扬声器振动及系统音频延迟影响，不能替代专业起跑器或用于正式比赛判罚。", color = RT.muted)
            Text("数据与权限", color = RT.ink)
            Text("运动采样仅在本机处理，服务器只保存时间差、检测状态及发令设置。未检测到移动不会记作零。单人模式不需要另一台手机；联网登录后同步记录。", color = RT.muted)
            Text("版本 ${cc.runtick.app.BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = RT.muted)
            TextButton(onClick = { uri.openUri("https://runtick.cc") }) { Text("访问官网 ↗", color = RT.deep) }
        }
        RTButton(onClick = onBack) { RTButtonInkText("返回我的") }
    }
}

@Composable fun SoloHistoryView(api: APIClient, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf(SoloRecords()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    suspend fun load(reset: Boolean = true) {
        if (loading) return
        loading = true
        try {
            val data = api.request("/api/starts?offset=${if (reset) 0 else history.records.size}") { AppJson.decodeFromJsonElement(SoloRecords.serializer(), it) }
            history = if (reset) data else data.copy(records = history.records + data.records)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "读取失败" }
        finally { loading = false }
    }
    LaunchedEffect(Unit) { load() }
    RTPage {
        RTButton(onClick = onBack) { RTButtonInkText("返回") }
        RTBrand(); RTHero(badge = "训练档案", title = "单人起跑记录", subtitle = "手机运动检测估算 · 不计入距离成绩或 PB")
        RTCard {
            if (history.records.isEmpty()) Text("暂无记录", color = RT.muted)
            history.records.forEach { record ->
                if (record.reactionStatus == "false_start") RTChip("抢跑", lemon = true)
                Text(reactionText(record.reactionMs, record.reactionStatus), color = RT.ink)
                Text(Date(record.createdAt.toLong()).toString(), fontSize = 12.sp, color = RT.muted)
            }
            if (history.hasMore) RTButton(onClick = { scope.launch { load(false) } }, enabled = !loading) { RTButtonInkText("加载更多") }
            RTButton(onClick = { scope.launch { load() } }, enabled = !loading) { RTButtonInkText("刷新记录") }
        }
    }
    RTErrorAlert(error, enabled = true)
}


suspend fun loadStarterSettings(api: APIClient): List<StageDelay> = api.request("/api/auth/starter-settings") {
    AppJson.decodeFromJsonElement(StarterSettingsResponse.serializer(), it).starterSettings
}
@Composable fun UserStarterSettingsView(api: APIClient, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var stages by remember { mutableStateOf(StageDelay.defaults()) }
    var loading by remember { mutableStateOf(true) }; var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }; var saved by remember { mutableStateOf(false) }
    suspend fun load() { error = ""; try { stages = loadStarterSettings(api); loading = false } catch(e: Exception) { error = e.message ?: "读取失败" } }
    LaunchedEffect(api) { load() }
    RTPage {
        RTButton(onClick = onBack) { RTButtonInkText("返回我的") }
        RTHero(badge = "个人偏好", title = "起跑设置", subtitle = "保存到当前账号；担任起点时用于新一轮发令。")
        RTCard(title = "发令时间") {
            StageSettings(stages, disabled = loading || saving, allowSetToggle = true, onChange = { stages = it })
            Text("是否播放各就各位在每次开始时选择；跳过时也跳过该段等待。修改仅对新一轮生效。", color = RT.muted)
        }
        if (loading) CircularProgressIndicator()
        RTButton(onClick = { scope.launch {
            saving = true; saved = false; error = ""
            try {
                stages = api.request("/api/auth/starter-settings", "PUT", buildJsonObject { put("starterSettings", AppJson.encodeToJsonElement(ListSerializer(StageDelay.serializer()), stages)) }) {
                    AppJson.decodeFromJsonElement(StarterSettingsResponse.serializer(), it).starterSettings
                }; saved = true
            } catch(e: Exception) { error = e.message ?: "保存失败" } finally { saving = false }
        } }, enabled = !loading && !saving, secondary = false) { RTButtonText(if(saving) "正在保存…" else "保存设置") }
        if (saved) Text("起跑设置已保存到账号", color = RT.deep)
        if (error.isNotEmpty()) { Text(error, color = RT.ink); if(loading) RTButton(onClick = { scope.launch { load() } }) { RTButtonInkText("重新加载") } }
    }
}
