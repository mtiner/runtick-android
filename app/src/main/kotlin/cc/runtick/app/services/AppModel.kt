package cc.runtick.app.services

import android.content.Context
import cc.runtick.app.BuildConfig
import cc.runtick.app.core.AppConfiguration
import cc.runtick.app.core.AppJson
import cc.runtick.app.core.UpdateDecision
import cc.runtick.app.core.decodeVersionInfo
import cc.runtick.app.core.evaluateUpdate
import cc.runtick.app.core.ClientError
import cc.runtick.app.core.ClockMapping
import cc.runtick.app.core.ClockResponse
import cc.runtick.app.core.ClockSample
import cc.runtick.app.core.HubState
import cc.runtick.app.core.MemberState
import cc.runtick.app.core.MonotonicClock
import cc.runtick.app.core.PendingEvent
import cc.runtick.app.core.Race
import cc.runtick.app.core.RoleSelection
import cc.runtick.app.core.SavedResult
import cc.runtick.app.core.StageDelay
import cc.runtick.app.core.TimingEvent
import cc.runtick.app.core.User
import cc.runtick.app.core.decodeClock
import cc.runtick.app.core.decodeCurrentRoomCode
import cc.runtick.app.core.decodeHistoryRecord
import cc.runtick.app.core.decodeRace
import cc.runtick.app.core.decodeRoom
import cc.runtick.app.core.decodeRoomDetail
import cc.runtick.app.core.decodeUser
import cc.runtick.app.network.APIClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.pow

/** 计时事件请求体：{kind, at, uncertaintyMs}。 */
private fun eventBody(kind: String, at: Double, uncertaintyMs: Double): JsonObject = buildJsonObject {
    put("kind", kind)
    put("at", at)
    put("uncertaintyMs", uncertaintyMs)
}

/**
 * 主线程共享状态：登录、房间进出与待补交事件重放。
 * 字段保留可直接读取的 var，同时以 StateFlow 暴露给 Compose。
 */
class AppModel(private val appContext: Context, store: PendingStore? = null) {

    private val pendingStore: PendingStore = store ?: SharedPreferencesPendingStore(appContext)

    var api: APIClient? = null
        private set

    private val _user = MutableStateFlow<User?>(null)
    var user: User?
        get() = _user.value
        set(value) { _user.value = value }
    val userFlow: StateFlow<User?> get() = _user

    private val _loading = MutableStateFlow(false)
    var loading: Boolean
        get() = _loading.value
        set(value) { _loading.value = value }
    val loadingFlow: StateFlow<Boolean> get() = _loading

    private val _lobbyReady = MutableStateFlow(false)
    var lobbyReady: Boolean
        get() = _lobbyReady.value
        set(value) { _lobbyReady.value = value }
    val lobbyReadyFlow: StateFlow<Boolean> get() = _lobbyReady

    private val _error = MutableStateFlow("")
    var error: String
        get() = _error.value
        set(value) { _error.value = value }
    val errorFlow: StateFlow<String> get() = _error

    private val _update = MutableStateFlow<UpdateDecision?>(null)
    var update: UpdateDecision?
        get() = _update.value
        set(value) { _update.value = value }
    val updateFlow: StateFlow<UpdateDecision?> get() = _update

    private val _room = MutableStateFlow<RoomModel?>(null)
    var room: RoomModel?
        get() = _room.value
        set(value) { _room.value = value }
    val roomFlow: StateFlow<RoomModel?> get() = _room

    /** 创建（或重建）API 客户端并挂上 401 回调。 */
    private fun configure() {
        api = APIClient(AppConfiguration.SERVER_URL, appContext).also { client ->
            client.unauthorized = { clearSession() }
        }
    }

    /** 串行化用户操作：loading 期间直接忽略新请求。 */
    suspend fun perform(block: suspend () -> Unit) {
        if (loading) return
        loading = true
        error = ""
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.message ?: "发生未知错误"
        } finally {
            loading = false
        }
    }

    /** 启动时恢复会话：/auth/me 成功后顺带补交 pending 并退出旧房间。 */
    suspend fun restore() = perform {
        configure()
        val client = api ?: return@perform
        try {
            user = client.request("/api/auth/me") { decodeUser(it) }
            leave()
        } catch (e: ClientError) {
            // 全新安装没有已保存会话属正常情况，不算连接失败
            if (e.status != 401) throw e
        }
    }

    /** 登录：成功后补交 pending 并退出旧房间。 */
    suspend fun login(id: String, password: String) {
        if (id.isBlank() || password.isEmpty()) {
            throw ClientError(0, null, "请输入用户 ID 和密码")
        }
        configure()
        val client = api!!
        user = client.request(
            "/api/auth/login", "POST",
            buildJsonObject { put("userId", id); put("password", password) },
        ) { decodeUser(it) }
        leave()
    }

    /** 登出：通知服务器、清 Cookie、停房间。 */
    suspend fun logout() {
        try {
            api?.request("/api/auth/logout", "POST") { }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.message ?: "发生未知错误"
        }
        clearSession()
    }

    /** 进入房间：code 为 null 时创建，否则加入 4 位数字房间。进入前先退出旧房间（补交 pending）。 */
    suspend fun enter(code: String? = null) {
        val client = api ?: return
        val currentUser = user ?: return
        if (code != null && (code.length != 4 || code.any { !it.isDigit() })) {
            throw ClientError(0, null, "房间号必须为 4 位数字")
        }
        leave()
        val path = if (code != null) "/api/rooms/$code/join" else "/api/rooms"
        val roomData = client.request(path, "POST") { decodeRoom(it) }
        val model = RoomModel(appContext, client, currentUser, roomData.code)
        room = model
        lobbyReady = false
        model.start()
    }

    /** 退出房间：先补交所有 pending（失败则不退出），再通知服务器。 */
    suspend fun leave() {
        val client = api ?: return
        replayPending()
        client.request("/api/rooms/leave", "POST") { }
        room?.stop()
        room = null
        lobbyReady = true
    }

    /** 后台解析当前房间：有房间且未打开时打开，无房间则离开。 */
    suspend fun openCurrentRoom() {
        val client = api ?: return
        val currentUser = user ?: return
        val current = client.request("/api/rooms/current") { decodeCurrentRoomCode(it) }
        if (current == null) {
            leave()
            return
        }
        if (room?.code == current) {
            room?.resume()
            return
        }
        room?.stop()
        val model = RoomModel(appContext, client, currentUser, current)
        room = model
        lobbyReady = false
        model.start()
    }

    /** 遍历本用户全部 pending key，逐个补交成功后删除。 */
    suspend fun replayPending() {
        val client = api ?: return
        val currentUser = user ?: return
        val prefix = "pending:${AppConfiguration.SERVER_URL.trimEnd('/')}:${currentUser.id}:"
        for (key in pendingStore.keysWithPrefix(prefix)) {
            val pending = pendingStore.load(key) ?: continue
            if (pending.userId != currentUser.id) continue
            val event = pending.event
            client.request(
                "/api/rooms/${pending.code}/races/${pending.raceId}/events", "POST",
                eventBody(event.kind, event.at, event.uncertaintyMs),
            ) { decodeRace(it) }
            pendingStore.delete(key)
        }
    }

    /** 清空本地会话（401 或登出）。 */
    fun clearSession() {
        room?.stop()
        room = null
        user = null
        lobbyReady = false
        api?.clearCookies()
    }

    /**
     * 冷启动检查 App 更新：24 小时节流；网络失败或解析失败一律静默忽略，
     * 仅在服务器版本更高时把判定结果写入 updateFlow 供弹窗展示。
     */
    suspend fun checkAppUpdate() {
        val client = api ?: return
        val prefs = appContext.getSharedPreferences("runtick.update", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("lastCheckAt", 0L) < UPDATE_CHECK_INTERVAL_MS) return
        update = try {
            val info = client.request("/api/app/version?platform=android") { decodeVersionInfo(it) }
            // 仅在请求成功后写入节流时间戳，避免网络故障后 24 小时内无法重试
            prefs.edit().putLong("lastCheckAt", now).apply()
            evaluateUpdate(BuildConfig.VERSION_CODE, info)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
    }

    /** 关闭非强制更新弹窗。 */
    fun dismissUpdate() {
        update = null
    }

    companion object {
        /** 更新检查节流间隔：24 小时。 */
        private const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }
}

/**
 * 房间状态机：校时、角色选择、发令/冲线、事件补交与 WebSocket 同步。
 * 所有字段保留可直接读取的 var，同时以 StateFlow 暴露给 Compose。
 */
class RoomModel(
    private val context: Context,
    private val api: APIClient,
    private val user: User,
    val code: String,
    store: PendingStore? = null,
) {

    private val prefs = context.getSharedPreferences("runtick.room", Context.MODE_PRIVATE)
    private val pendingStore: PendingStore = store ?: SharedPreferencesPendingStore(context)
    private val audio = StarterAudio(context)
    private val reactionMotion = ReactionMotion(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val pendingKey = "pending:${api.baseUrl.trimEnd('/')}:${user.id}:$code"
    private val roleSelection = RoleSelection()
    private var eventClock: ClockMapping? = null
    private var webSocket: WebSocket? = null
    private var stopped = false
    private var started = false
    private var receivedRoomState = false
    private var autoRetry = true
    private var retryAfter = 0.0

    private val _members = MutableStateFlow<List<MemberState>>(emptyList())
    var members: List<MemberState>
        get() = _members.value
        set(value) { _members.value = value }
    val membersFlow: StateFlow<List<MemberState>> get() = _members

    private val _connected = MutableStateFlow(false)
    var connected: Boolean
        get() = _connected.value
        set(value) { _connected.value = value }
    val connectedFlow: StateFlow<Boolean> get() = _connected

    private val _ready = MutableStateFlow(false)
    var ready: Boolean
        get() = _ready.value
        set(value) { _ready.value = value }
    val readyFlow: StateFlow<Boolean> get() = _ready

    private val _race = MutableStateFlow<Race?>(null)
    var race: Race?
        get() = _race.value
        set(value) { _race.value = value }
    val raceFlow: StateFlow<Race?> get() = _race

    private val _clock = MutableStateFlow<ClockMapping?>(null)
    var clock: ClockMapping?
        get() = _clock.value
        set(value) { _clock.value = value }
    val clockFlow: StateFlow<ClockMapping?> get() = _clock

    private val _syncing = MutableStateFlow(false)
    var syncing: Boolean
        get() = _syncing.value
        set(value) { _syncing.value = value }
    val syncingFlow: StateFlow<Boolean> get() = _syncing

    private val _progress = MutableStateFlow(0)
    var progress: Int
        get() = _progress.value
        set(value) { _progress.value = value }
    val progressFlow: StateFlow<Int> get() = _progress

    private val _error = MutableStateFlow("")
    var error: String
        get() = _error.value
        set(value) { _error.value = value }
    val errorFlow: StateFlow<String> get() = _error

    private val _busy = MutableStateFlow(false)
    var busy: Boolean
        get() = _busy.value
        set(value) { _busy.value = value }
    val busyFlow: StateFlow<Boolean> get() = _busy

    private val _firing = MutableStateFlow(false)
    var firing: Boolean
        get() = _firing.value
        set(value) { _firing.value = value }
    val firingFlow: StateFlow<Boolean> get() = _firing

    private val _cue = MutableStateFlow<String?>(null)
    var cue: String?
        get() = _cue.value
        set(value) { _cue.value = value }
    val cueFlow: StateFlow<String?> get() = _cue

    private val _pressed = MutableStateFlow(false)
    var pressed: Boolean
        get() = _pressed.value
        set(value) { _pressed.value = value }
    val pressedFlow: StateFlow<Boolean> get() = _pressed

    private val _pending = MutableStateFlow<PendingEvent?>(null)
    var pending: PendingEvent?
        get() = _pending.value
        set(value) { _pending.value = value }
    val pendingFlow: StateFlow<PendingEvent?> get() = _pending

    private val _submitting = MutableStateFlow(false)
    var submitting: Boolean
        get() = _submitting.value
        set(value) { _submitting.value = value }
    val submittingFlow: StateFlow<Boolean> get() = _submitting

    private val _stages = MutableStateFlow(StageDelay.defaults())
    var stages: List<StageDelay>
        get() = _stages.value
        set(value) {
            _stages.value = value
            if (value.size == 3 && value.all { it.valid }) {
                prefs.edit()
                    .putString("starter", AppJson.encodeToString(ListSerializer(StageDelay.serializer()), value))
                    .apply()
            }
        }
    val stagesFlow: StateFlow<List<StageDelay>> get() = _stages

    init {
        val saved = prefs.getString("starter", null)?.let { raw ->
            runCatching { AppJson.decodeFromString(ListSerializer(StageDelay.serializer()), raw) }.getOrNull()
        }
        if (saved != null && saved.size == 3 && saved.all { it.valid }) {
            _stages.value = saved
        }
        pending = pendingStore.load(pendingKey)
        scope.launch { TrainingSync.retry(context, api, user.id) }
    }

    /** members 中自己的服务端角色。 */
    val serverRole: String?
        get() = members.firstOrNull { it.loginId == user.loginId }?.deviceRole

    /** 展示角色：乐观值优先。 */
    val role: String?
        get() = roleSelection.effective(serverRole)

    /** 角色请求是否在途。 */
    val roleSaving: Boolean
        get() = roleSelection.saving

    /** 本轮比赛角色：按 race 的 start/finish 用户判定。 */
    val raceRole: String?
        get() {
            val current = race ?: return null
            return when (user.id) {
                current.startUserId -> "start"
                current.finishUserId -> "finish"
                else -> null
            }
        }

    /** 启动三个协程：初始校时、1 秒轮询 /races（含自动补交）、WebSocket 同步。 */
    fun start() {
        if (started || stopped) return
        started = true
        scope.launch { calibrate() }
        scope.launch { pollLoop() }
        scope.launch { socketLoop() }
    }

    /** 接受不回退的 race 更新；比赛结束即复位发令状态。 */
    fun update(value: Race) {
        val current = race
        if (current != null && !current.accepts(value)) return
        race = value
        if (!value.active) interrupt()
    }

    /** 停止全部协程与 WebSocket。 */
    fun stop() {
        stopped = true
        scope.cancel()
        runCatching { webSocket?.cancel() }
        webSocket = null
        connected = false
        audio.stop(); reactionMotion.stop()
        interrupt()
    }

    /** 退后台：取消按压、停音频、清提示。 */
    fun interrupt() {
        pressed = false
        eventClock = null
        audio.stop(); reactionMotion.stop()
        firing = false
        cue = null
    }

    /** 回前台：比赛进行中则重新校时，并让 WebSocket 立刻心跳一次。 */
    fun resume() {
        if (race?.active == true) scope.launch { calibrate() }
        runCatching { webSocket?.send("{\"type\":\"ping\"}") }
    }

    /** 20 次时钟采样并汇总为 ClockMapping。 */
    suspend fun calibrate() {
        if (syncing || pressed || firing) return
        syncing = true
        progress = 0
        error = ""
        try {
            val samples = mutableListOf<ClockSample>()
            for (i in 0 until 20) {
                currentCoroutineContext().ensureActive()
                val sent = MonotonicClock.now()
                val response: ClockResponse = api.request(
                    "/api/clock?clientSentAt=$sent&nonce=${UUID.randomUUID()}",
                ) { decodeClock(it) }
                val received = MonotonicClock.now()
                if (response.clientSentAt != sent ||
                    response.sentAt < response.receivedAt ||
                    response.processingMs != response.sentAt - response.receivedAt ||
                    received - sent < response.processingMs
                ) {
                    throw ClientError(0, null, "校时响应无效，请重试")
                }
                samples.add(ClockSample(sent, received, response.receivedAt, response.sentAt))
                progress = i + 1
            }
            clock = ClockMapping.average(samples)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            clock = null
            error = e.message ?: "发生未知错误"
        } finally {
            syncing = false
        }
    }

    /** 乐观选择设备角色，成功后补发一次 sync。 */
    suspend fun pick(deviceRole: String) {
        if (race?.active == true || !connected || busy || stopped) return
        if (roleSelection.saving || deviceRole == role) return
        // 在等待 HTTP 返回前先更新状态，界面立即有反馈
        roleSelection.choose(deviceRole)
        busy = true
        error = ""
        try {
            api.request(
                "/api/rooms/$code/role", "POST",
                buildJsonObject { put("deviceRole", deviceRole) },
            ) { }
            roleSelection.confirmServer(serverRole)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            roleSelection.fail()
            error = e.message ?: "发生未知错误"
        }
        busy = false
        // sync 失败不能回滚已成功的 HTTP 角色变更
        runCatching { webSocket?.send("{\"type\":\"sync\"}") }
    }

    /** 开始新比赛（action=start）或进入下一轮（action=next）。 */
    suspend fun raceAction(action: String = "start") = withBusy {
        val currentRace = race
        val path = if (action == "start") "/api/rooms/$code/races"
        else "/api/rooms/$code/races/${currentRace?.id ?: throw ClientError(0, null, "当前没有比赛")}/$action"
        val data = api.request(path, "POST") { decodeRace(it) }
        data?.let { update(it) }
    }

    /** 起点发令：校时新鲜且尚未有 start 事件时，播放三段音频并在枪声时刻 capture。 */
    suspend fun fire() {
        val snapshot = clock
        val pass = snapshot != null && snapshot.fresh && !syncing && !firing &&
            race?.active == true && race?.startAt == null && pending == null && raceRole == "start"
        if (!pass) {
            if (clock?.fresh != true) {
                calibrate()
                error = "校时已更新，请再次发令"
            }
            return
        }
        val clockSnapshot = snapshot!!
        firing = true
        try {
            cue = "请保持手机与起跑器稳定…"
            reactionMotion.prepare()
            val clicked = MonotonicClock.now()
            val (gunAt, extra) = audio.fire(clicked, stages) { cue = it }
            capture("start", gunAt, clockSnapshot, extra)
            val raceId = race?.id
            if (raceId != null) reactionMotion.gun(gunAt) { result ->
                race = race?.copy(reactionMs = result.reactionMs, reactionStatus = result.reactionStatus)
                scope.launch {
                    try { TrainingSync.send(context, api, user.id, "/api/rooms/$code/races/$raceId/reaction", TrainingSync.reactionBody(result.reactionMs, result.reactionStatus), raceId) }
                    catch (e: Exception) { error = "反应时已保留，联网后重试：${e.message}" }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.message ?: "发生未知错误"
            audio.stop(); reactionMotion.stop()
        } finally {
            firing = false
        }
    }

    /** 终点按下：只锁定校时快照，不产生事件。 */
    fun press() {
        val snapshot = clock
        if (snapshot == null || !snapshot.fresh || syncing || pending != null ||
            race?.active != true || race?.finishAt != null || raceRole != "finish"
        ) {
            return
        }
        eventClock = snapshot
        pressed = true
    }

    /**
     * 终点松手：capture finish 事件。
     * at 为 UI 传入的 MonotonicClock.now() + clock.offsetMs（已是服务器时间域），不再叠加偏移。
     */
    fun release(at: Double) {
        val snapshot = eventClock ?: return
        pressed = false
        eventClock = null
        if (race?.active != true) return
        capture("finish", at, snapshot, 0.0, localTime = false)
    }

    /** 取消按压。 */
    fun cancelPress() {
        pressed = false
        eventClock = null
    }

    /** 提交 pending 事件；失败保留并按 autoRetry 策略稍后重试。 */
    suspend fun submit() {
        val current = pending ?: return
        if (submitting || stopped) return
        submitting = true
        try {
            val event = current.event
            val data = api.request(
                "/api/rooms/$code/races/${current.raceId}/events", "POST",
                eventBody(event.kind, event.at, event.uncertaintyMs),
            ) { decodeRace(it) }
            pending = null
            pendingStore.delete(pendingKey)
            data?.let { update(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = (e.message ?: "发生未知错误") + "；原始时间戳已保留"
            val status = (e as? ClientError)?.status ?: 0
            // 网络错误或服务端 5xx 才自动重试
            autoRetry = status == 0 || status >= 500
            retryAfter = MonotonicClock.now() + 3000.0
        } finally {
            submitting = false
        }
    }

    /** 保存成绩（仅终点角色）。 */
    suspend fun save(distance: Int) {
        val currentRace = race
        if (distance !in 1..100000 || currentRace == null) {
            error = "请输入 1–100000 米的整数距离"
            return
        }
        withBusy {
            val record = api.request(
                "/api/rooms/$code/races/${currentRace.id}/result", "POST",
                buildJsonObject { put("distance", distance) },
            ) { decodeHistoryRecord(it) }
            val base = race ?: currentRace
            update(base.copy(savedResult = SavedResult(record.distance, record.isPb, record.pbImprovementMs)))
        }
    }

    // ---------- 内部实现 ----------

    /** 1 秒轮询比赛状态，含 pending 自动补交。 */
    private suspend fun pollLoop() {
        try {
            val detail = api.request("/api/rooms/$code") { decodeRoomDetail(it) }
            if (!receivedRoomState) members = detail.members
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.message ?: "发生未知错误"
        }
        while (!stopped && currentCoroutineContext().isActive) {
            try {
                val data = api.request("/api/rooms/$code/races") { decodeRace(it) }
                data?.let { update(it) }
                if (pending != null && autoRetry && MonotonicClock.now() >= retryAfter) submit()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.message ?: "发生未知错误"
            }
            delay(1000)
        }
    }

    /** WebSocket 循环：断线指数退避重连，收到首个 state 即恢复。 */
    private suspend fun socketLoop() {
        var retry = 0
        while (!stopped && currentCoroutineContext().isActive) {
            connected = false
            ready = false
            val receivedState = AtomicBoolean(false)
            val closed = CompletableDeferred<Throwable>()
            val ws = api.client.newWebSocket(api.socketRequest(code), object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleSocketMessage(text, receivedState)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closed.complete(ClientError(code, null, reason.ifEmpty { "连接已关闭" }))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    closed.complete(t)
                }
            })
            webSocket = ws
            val heartbeat = scope.launch {
                while (currentCoroutineContext().isActive) {
                    delay(30_000)
                    runCatching { ws.send("{\"type\":\"ping\"}") }
                }
            }
            try {
                ws.send("{\"type\":\"sync\"}")
                throw closed.await()
            } catch (e: CancellationException) {
                heartbeat.cancel()
                runCatching { ws.cancel() }
                throw e
            } catch (e: Throwable) {
                connected = false
                ready = false
            }
            heartbeat.cancel()
            runCatching { ws.cancel() }
            if (stopped || !currentCoroutineContext().isActive) break
            // 收到过 state 说明链路正常，退避从零开始
            retry = if (receivedState.get()) 0 else retry + 1
            val backoffSeconds = min(2.0.pow(min(retry, 4).toDouble()), 10.0)
            delay((backoffSeconds * 1000).toLong())
        }
    }

    /** 处理 state 消息；其余（含 pong）忽略。状态更新统一切回主线程。 */
    private fun handleSocketMessage(text: String, receivedState: AtomicBoolean) {
        val event = runCatching { AppJson.decodeFromString(HubState.serializer(), text) }.getOrNull() ?: return
        if (event.type != "state") return
        receivedState.set(true)
        scope.launch {
            connected = true
            receivedRoomState = true
            members = event.members ?: emptyList()
            roleSelection.receivedServerState()
            ready = event.ready ?: false
        }
    }

    /** busy 互斥的操作包装。 */
    private suspend fun withBusy(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = ""
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.message ?: "发生未知错误"
        } finally {
            busy = false
        }
    }

    /** 生成 pending：内存 + 落盘，并立即尝试后台提交。 */
    private fun capture(kind: String, at: Double, clockSnapshot: ClockMapping, extra: Double = 0.0, localTime: Boolean = true) {
        if (pending != null) return
        val currentRace = race ?: return
        autoRetry = true
        val serverAt = if (localTime) at + clockSnapshot.offsetMs else at
        val value = PendingEvent(
            code, currentRace.id, user.id,
            TimingEvent(kind, serverAt, clockSnapshot.uncertaintyMs + extra),
        )
        pending = value
        pendingStore.save(pendingKey, value)
        scope.launch { submit() }
    }
}
