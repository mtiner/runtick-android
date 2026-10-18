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
@Composable fun StageSettings(stages: List<StageDelay>, disabled: Boolean = false, onChange: (List<StageDelay>) -> Unit) {
    fun update(index: Int, stage: StageDelay) { onChange(stages.toMutableList().also { it[index] = stage.normalized() }) }
    Row {
        Text("检测起跑反应时", modifier = Modifier.weight(1f), color = RT.ink)
        Switch(checked = stages[0].reactionEnabled, onCheckedChange = { update(0, stages[0].copy(reactionEnabled = it)) }, enabled = !disabled)
    }
    Text("有起跑器并固定手机后开启；三点式起跑可关闭，不影响发令或跑步成绩。", fontSize = 12.sp, color = RT.muted)
    Row {
        Text("播放各就位口令", modifier = Modifier.weight(1f), color = RT.ink)
        Switch(checked = stages[0].enabled, onCheckedChange = { update(0, stages[0].copy(enabled = it)) }, enabled = !disabled)
    }
    stages.forEachIndexed { index, stage ->
        if (index != 0 || stages[0].enabled) {
            val title = listOf("开始 → 各就位", if (stages[0].enabled) "各就位结束 → 预备" else "开始 → 预备", "预备结束 → 枪声")[index]
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
    val prefs = remember { context.getSharedPreferences("runtick.room", Context.MODE_PRIVATE) }
    var stages by remember { mutableStateOf(runCatching { AppJson.decodeFromString(ListSerializer(StageDelay.serializer()), prefs.getString("starter", "")!!) }.getOrNull()?.takeIf { it.size == 3 } ?.map { it.normalized() } ?: StageDelay.defaults()) }
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
        prefs.edit().putString("starter", AppJson.encodeToString(ListSerializer(StageDelay.serializer()), saved)).apply()
        task = scope.launch {
            try {
                motion.prepare(saved[0].reactionEnabled)
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
                StageSettings(stages, onChange = { stages = it })
                Text("手机贴靠或固定在起跑器上并保持稳定。反应时为设备运动估算，非专业起跑器计时；小于 100 ms 标注抢跑。", fontSize = 12.sp, color = RT.muted)
                AudioPreparationCard()
                RTButton(onClick = { start() }, secondary = false) { RTButtonText("确认设置，开始发令") }
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
