package cc.runtick.app.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.House
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.runtick.app.core.User
import cc.runtick.app.services.AppModel
import kotlinx.coroutines.launch

/**
 * 根视图：首次引导 → 双 Tab（房间详情 / 我的）。
 * 房间详情按登录与房间状态显示登录页、大厅或房间视图。
 */
@Composable
fun RootView(model: AppModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("runtick", Context.MODE_PRIVATE) }

    // 订阅 AppModel 状态流。
    val user by model.userFlow.collectAsState()
    val loading by model.loadingFlow.collectAsState()
    val error by model.errorFlow.collectAsState()
    val room by model.roomFlow.collectAsState()

    var onboarded by remember { mutableStateOf(prefs.getBoolean("onboarded", false)) }
    var showGuideHelp by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }

    // 0 = 房间详情，1 = 我的
    var destination by rememberSaveable { mutableIntStateOf(0) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showProfileHistory by rememberSaveable { mutableStateOf(false) }

    // 登录状态变化时回到房间详情 Tab。
    LaunchedEffect(user?.id) {
        destination = 0
        showProfileHistory = false
        showAbout = false
    }
    // 切到「我的」打断房间内触控/发令；切回「房间详情」重新打开当前房间。
    LaunchedEffect(destination) {
        if (destination != 0) {
            model.room?.interrupt()
        } else if (model.user != null && !model.loading) {
            try { model.openCurrentRoom() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val target = model.room
                if (target != null) target.error = e.message ?: "操作未完成"
                else model.error = e.message ?: "操作未完成"
            }
        }
    }

    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { model.room?.resume(); model.scheduleSync() }
                Lifecycle.Event.ON_PAUSE -> model.room?.interrupt()
                else -> Unit
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }

    if (!onboarded) {
        Box(Modifier.fillMaxSize()) {
            GuideView(
                onProceed = {
                    prefs.edit().putBoolean("onboarded", true).apply()
                    onboarded = true
                },
                onHelp = { showGuideHelp = true }
            )
            if (showGuideHelp) RTGlobalHelpSheet(
                onDismiss = { showGuideHelp = false },
                onGuide = { /* 已在引导页内，仅关闭 */ }
            )
        }
        RTErrorAlert(error, enabled = true)
        return
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when (destination) {
                    0 -> when {
                        user == null -> LoginView(model, onHelp = { showHelp = true })
                        room != null -> RoomView(
                            model = room!!,
                            api = model.api,
                            user = user!!,
                            leave = {
                                val target = model.room
                                scope.launch {
                                    model.perform {
                                        target?.error = ""
                                        try { model.leave() }
                                        catch (e: CancellationException) { throw e }
                                        catch (e: Exception) { target?.error = e.message ?: "退出房间未完成" }
                                    }
                                }
                            }
                        )
                        else -> LobbyView(model, onHelp = { showHelp = true })
                    }
                    2 -> if (user != null && model.api != null) SoloStartView(model.api!!, user!!) else LoginView(model, onHelp = { showHelp = true })
                    else -> if (user != null) {
                        ProfileView(model = model, user = user!!, onOpenHistory = { showProfileHistory = true }, onAbout = { showAbout = true }, onHelp = { showHelp = true })
                    } else LoginView(model, onHelp = { showHelp = true })
                }
            }
            NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                NavigationBarItem(
                    selected = destination == 0,
                    onClick = { destination = 0 },
                    icon = { Icon(Icons.Filled.House, contentDescription = null) },
                    label = { Text("房间详情") },
                    colors = tabColors()
                )
                NavigationBarItem(
                    selected = destination == 2, onClick = { destination = 2 },
                    icon = { Icon(Icons.Filled.AddCircle, contentDescription = null) },
                    label = { Text("起跑发令") }, colors = tabColors()
                )
                NavigationBarItem(
                    selected = destination == 1,
                    onClick = { destination = 1 },
                    icon = {
                        Icon(
                            if (user != null) Icons.Filled.AccountCircle else Icons.Filled.Person,
                            contentDescription = null
                        )
                    },
                    label = { Text("我的") },
                    colors = tabColors()
                )
            }
        }

        // 个人中心的历史成绩全屏页。
        if (showProfileHistory && user != null && model.api != null) {
            HistoryView(api = model.api!!, user = user!!, room = null, onBack = { showProfileHistory = false })
        }

        if (showAbout) AboutView(onBack = { showAbout = false })

        // 全局 loading 遮罩：半透明圆角背景 + 进度圈。
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .background(Color.Black.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
                        .padding(28.dp)
                ) {
                    CircularProgressIndicator(color = RT.deep)
                }
            }
        }
    }
    // 房间未打开时才用全局弹窗；房间内错误由 RoomView 自己弹。
    RTErrorAlert(error, enabled = room == null)

    // 使用帮助弹层。
    if (showHelp) RTGlobalHelpSheet(
        onDismiss = { showHelp = false },
        onGuide = {
            // 前往权限检测页：重新进入引导页，完成后返回主框架。
            showHelp = false
            onboarded = false
        }
    )
}

@Composable
private fun tabColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = RT.deep,
    selectedTextColor = RT.ink,
    indicatorColor = RT.aqua.copy(alpha = 0.22f),
    unselectedIconColor = RT.muted,
    unselectedTextColor = RT.muted
)

/** 登录页。 */
@Composable
fun LoginView(model: AppModel, onHelp: () -> Unit = {}) {
    var login by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val loading by model.loadingFlow.collectAsState()

    RTPage {
        RTBrand(showHelp = true, onHelp = onHelp, modifier = Modifier.rtEntrance(0))
        RTHero(
            badge = "账号登录",
            title = "登录，开始计时",
            subtitle = "首次使用的 ID 将自动创建账号，\n请记住 ID 和密码。",
            modifier = Modifier.rtEntrance(40)
        )
        RTCard(modifier = Modifier.rtEntrance(80)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("用户 ID", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = RT.ink)
                RTTextField(
                    value = login,
                    onValueChange = { login = it },
                    placeholder = "自定义 ID，全局唯一",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("密码", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = RT.ink)
                RTTextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = "请输入密码",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation()
                )
            }
            RTButton(
                onClick = {
                    scope.launch {
                        model.perform {
                            model.login(id = login, password = password)
                            password = ""
                        }
                    }
                },
                secondary = false,
                enabled = !loading
            ) { RTButtonText(if (loading) "登录中…" else "登录") }
        }
        Text(
            "登录即可创建或加入计时房间",
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            fontSize = 12.sp, color = RT.muted, textAlign = TextAlign.Center
        )
        RTBrandFooter()
    }
}

/** 大厅：创建房间 / 输入 4 位房间号加入。 */
@Composable
fun LobbyView(model: AppModel, onHelp: () -> Unit = {}) {
    var code by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val loading by model.loadingFlow.collectAsState()
    val lobbyReady by model.lobbyReadyFlow.collectAsState()

    fun enterRoom(codeValue: String) {
        scope.launch { model.perform { model.enter(code = codeValue) } }
    }

    RTPage {
        RTBrand(showHelp = true, onHelp = onHelp, modifier = Modifier.rtEntrance(0))
        RTHero(
            badge = "房间入口",
            title = "创建或加入房间",
            subtitle = "两台手机，一个房间。\n准备好，就一起出发。",
            modifier = Modifier.rtEntrance(40)
        )
        RTCard(modifier = Modifier.rtEntrance(80)) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Filled.AddCircle, contentDescription = null, tint = RT.deep, modifier = Modifier.size(32.dp))
                Text("创建房间", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = RT.ink)
                Text("邀请另一台手机加入，开始这一组训练。", fontSize = 14.sp, color = RT.muted, textAlign = TextAlign.Center)
            }
            RTButton(
                onClick = { scope.launch { model.perform { model.enter() } } },
                secondary = false,
                enabled = lobbyReady && !loading
            ) { RTButtonText("立即创建") }
        }
        RTCard(modifier = Modifier.rtEntrance(120)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.GridView, contentDescription = null, tint = RT.deep)
                Text("加入房间", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = RT.ink)
            }
            RTCodeInput(
                code = code,
                onCodeChange = { value ->
                    // 只保留数字并截断到 4 位；满 4 位自动加入。
                    code = value.filter { it.isDigit() }.take(4)
                    if (code.length == 4 && !loading && lobbyReady) enterRoom(code)
                }
            )
            RTButton(
                onClick = { enterRoom(code) },
                secondary = false,
                enabled = code.length == 4 && lobbyReady && !loading
            ) { RTButtonText("加入房间") }
        }
        if (!lobbyReady) {
            RTButton(onClick = { scope.launch { model.perform { model.openCurrentRoom(); model.scheduleSync() } } }) {
                RTButtonInkText("重试恢复房间")
            }
        }
    }
}

/** 首次引导页：品牌 + 协同示意 + 设备准备 + 声音准备。 */
@Composable
fun GuideView(
    onProceed: () -> Unit,
    onHelp: () -> Unit,
    continueTitle: String = "开始使用",
    showHelp: Boolean = true
) {
    val context = LocalContext.current
    var capabilities by rememberSaveable { mutableStateOf("点击检查设备能力") }

    RTPage {
        RTBrand(showHelp = showHelp, onHelp = onHelp, modifier = Modifier.rtEntrance(0))
        RTHero(
            badge = "开始之前",
            title = "两端协同，\n记录每次突破",
            subtitle = "起点发令，终点松手。\n让每一份努力，都有准确的刻度。",
            modifier = Modifier.rtEntrance(40)
        )
        RTCard(modifier = Modifier.rtEntrance(80)) {
            // 起点跑者 → 终点旗示意。
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.DirectionsRun, contentDescription = null, tint = RT.deep, modifier = Modifier.size(48.dp))
                Spacer(Modifier.size(20.dp))
                repeat(3) {
                    Box(Modifier.size(5.dp).background(RT.aqua, CircleShape))
                    Spacer(Modifier.size(5.dp))
                }
                Spacer(Modifier.size(20.dp))
                Icon(Icons.Filled.Flag, contentDescription = null, tint = RT.deep, modifier = Modifier.size(42.dp))
            }
            Text(
                "起点运动员播放原声发令；终点记录员提前按住屏幕，冲线时松手。",
                fontSize = 14.sp, lineHeight = 20.sp, color = RT.muted
            )
        }
        RTCard(title = "设备准备", modifier = Modifier.rtEntrance(120)) {
            Text(capabilities, fontSize = 12.sp, lineHeight = 17.sp, color = RT.muted)
            RTButton(onClick = {
                val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
                val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
                val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
                capabilities = "陀螺仪：${if (gyro) "可用" else "不支持"} · 加速度计：${if (accel) "可用" else "不支持"}\n登录状态会保留 · 房间内自动保持屏幕点亮"
            }) { RTButtonInkText("检查设备能力") }
        }
        NetworkPreparationCard()
        AudioPreparationCard(modifier = Modifier.rtEntrance(140))
        RTButton(
            onClick = onProceed,
            secondary = false,
            modifier = Modifier.rtEntrance(160)
        ) { RTButtonText(continueTitle) }
        Text(
            "请使用手机扬声器。计时估算误差不含人工反应。",
            modifier = Modifier.fillMaxWidth(),
            fontSize = 12.sp, color = RT.muted, textAlign = TextAlign.Center
        )
        RTBrandFooter()
    }
}

/** 个人中心。 */
@Composable
fun ProfileView(model: AppModel, user: User, onOpenHistory: () -> Unit, onAbout: () -> Unit = {}, onHelp: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val loading by model.loadingFlow.collectAsState()

    val syncNotice by model.syncNoticeFlow.collectAsState()
    val syncingRecords by model.syncingRecordsFlow.collectAsState()
    LaunchedEffect(user.id) { model.scheduleSync() }
    RTPage {
        RTBrand(showHelp = true, onHelp = onHelp, modifier = Modifier.rtEntrance(0))
        RTHero(badge = "个人中心", title = "我的", modifier = Modifier.rtEntrance(40))
        RTCard(modifier = Modifier.rtEntrance(80)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    Modifier.size(72.dp).background(RT.aqua.copy(alpha = 0.22f), RoundedCornerShape(22.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        user.loginId.take(1).uppercase(),
                        fontSize = 30.sp, fontWeight = FontWeight.Bold, color = RT.ink
                    )
                }
                Text(user.loginId, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = RT.ink)
                Text("账号 ID：${user.id}", fontSize = 12.sp, color = RT.muted)
                Text("● 已登录", fontSize = 12.sp, color = RT.deep)
            }
        }
        RTButton(onClick = onOpenHistory, modifier = Modifier.rtEntrance(120)) {
            Icon(Icons.Filled.BarChart, contentDescription = null, tint = RT.ink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            RTButtonInkText("历史成绩")
            Spacer(Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.ArrowRight, contentDescription = null, tint = RT.ink, modifier = Modifier.size(18.dp))
        }
        if (syncNotice.isNotEmpty()) RTCard(title = "训练记录同步") { Text(syncNotice, color = RT.muted) }
        RTButton(onClick = { model.scheduleSync(showConfirmation = true) }, enabled = !syncingRecords) {
            RTButtonInkText(if (syncingRecords) "正在同步…" else "同步本机训练记录")
        }
        RTButton(onClick = onAbout) { RTButtonInkText("关于 RunTick") }
        RTButton(
            onClick = { scope.launch { model.perform { model.logout() } } },
            enabled = !loading,
            modifier = Modifier.rtEntrance(160)
        ) {
            Icon(Icons.Filled.Power, contentDescription = null, tint = RT.ink, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            RTButtonInkText("退出登录")
        }
    }
}
