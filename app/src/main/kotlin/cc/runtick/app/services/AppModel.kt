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
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
class AppModel(private val appContext: Context, store: PendingStore? = null, private val suppliedClient: APIClient? = null) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var syncTask: Job? = null
    private var syncGeneration = UUID.randomUUID()
    private var profileRevision = UUID.randomUUID()
    private var resolvingRoom = false
    private val _syncNotice = MutableStateFlow("")
    val syncNoticeFlow: StateFlow<String> get() = _syncNotice
    private val _syncingRecords = MutableStateFlow(false)
    val syncingRecordsFlow: StateFlow<Boolean> get() = _syncingRecords

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

    private fun configure(reset: Boolean = false) {
        if (api == null || reset) api = suppliedClient ?: APIClient(AppConfiguration.SERVER_URL, appContext)
        user = api?.savedUser
        api?.let { client -> client.unauthorized = { if (api === client) clearSession() } }
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

    /** 启动时恢复会话及当前房间，后台补交本机记录。 */
    suspend fun restore() = perform {
        configure()
        val client = api ?: return@perform
        try {
            user = client.request("/api/auth/me") { decodeUser(it) }
            user?.let { client.remember(it) }
            openCurrentRoom()
            scheduleSync()
        } catch (e: ClientError) {
            // 全新安装没有已保存会话属正常情况，不算连接失败
            if (e.status != 401) throw e
        }
    }

    /** 登录后恢复当前房间，不主动退出。 */
    suspend fun login(id: String, password: String) {
        if (id.isBlank() || password.isEmpty()) {
            throw ClientError(0, null, "请输入用户 ID 和密码")
        }
        configure(reset = true)
        val client = api!!
        user = client.request(
            "/api/auth/login", "POST",
            buildJsonObject { put("userId", id); put("password", password) },
        ) { decodeUser(it) }
        user?.let { client.remember(it) }
        openCurrentRoom()
        scheduleSync()
    }

    suspend fun updateAvatar(filename: String) {
        val client = api ?: throw ClientError(401,null,"请先登录")
        val current = user ?: throw ClientError(401,null,"请先登录")
        val revision=UUID.randomUUID(); profileRevision=revision
        val updated = client.request("/api/auth/avatar","PATCH",buildJsonObject { put("avatar",filename) }) { decodeUser(it) }
        currentCoroutineContext().ensureActive()
        if (profileRevision != revision || api !== client || user?.id != current.id || updated.id != current.id) throw CancellationException()
        user = updated
        room?.let { model -> model.members = model.members.map { if (it.loginId == current.loginId) it.copy(avatar=updated.avatar) else it } }
        client.remember(updated)
    }
    suspend fun refreshProfile() {
        val client = api ?: return
        val current = user ?: return
        val revision=profileRevision
        val updated = client.request("/api/auth/me") { decodeUser(it) }
        if (profileRevision != revision || api !== client || user?.id != current.id || updated.id != current.id) return
        user = updated; client.remember(updated)
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

    /** 进入房间：code 为 null 时创建，否则加入 4 位数字房间。恢复房间状态后才允许创建或加入。 */
    suspend fun enter(code: String? = null) {
        val client = api ?: return
        val currentUser = user ?: return
        if (code != null && (code.length != 4 || code.any { !it.isDigit() })) {
            throw ClientError(0, null, "房间号必须为 4 位数字")
        }
        if (!lobbyReady) throw ClientError(0, null, "暂时无法确认房间状态，请重试恢复房间。")
        val path = if (code != null) "/api/rooms/$code/join" else "/api/rooms"
        val roomData = client.request(path, "POST") { decodeRoom(it) }
        val model = RoomModel(appContext, client, currentUser, roomData.code, pendingStore)
        room = model
        lobbyReady = false
        model.start()
    }

    /** 退出成员关系与后台同步分开处理，失败记录继续留在本机。 */
    suspend fun leave() {
        val client = api ?: return
        client.request("/api/rooms/leave", "POST") { }
        room?.stop(); room = null; lobbyReady = true
        scheduleSync()
    }

    suspend fun openCurrentRoom() {
        if(room!=null) room?.resume() else lobbyReady=true
    }

    fun scheduleSync(showConfirmation: Boolean = false) {
        if (syncTask?.isActive == true || user == null) return
        val token = UUID.randomUUID(); syncGeneration = token
        _syncingRecords.value = true
        syncTask = scope.launch {
            try {
                replayPending()
                if (syncGeneration == token && showConfirmation && _syncNotice.value.isEmpty() && user != null)
                    _syncNotice.value = "本机训练记录已同步。"
            } finally { if (syncGeneration == token) _syncingRecords.value = false }
        }
    }

    suspend fun replayPending() {
        val client = api ?: return
        val currentUser = user ?: return
        TrainingSync.retry(appContext, client, currentUser.id)
        val prefix = "pending:${client.baseUrl.trimEnd('/')}:${currentUser.id}:"
        for (key in pendingStore.keysWithPrefix(prefix)) {
            currentCoroutineContext().ensureActive()
            if (api !== client || user?.id != currentUser.id) return
            val pending = pendingStore.load(key) ?: continue
            if (pending.userId != currentUser.id) continue
            try {
                val event = pending.event
                client.request("/api/rooms/${pending.code}/races/${pending.raceId}/events", "POST",
                    AppJson.encodeToJsonElement(TimingEvent.serializer(), event).jsonObject) { decodeRace(it) }
                pendingStore.deleteIfUnchanged(key, pending)
                if (room?.pending == pending) room?.pending = null
            } catch (e: CancellationException) { throw e }
            catch (e: Throwable) {
                val status = (e as? ClientError)?.status ?: 0
                if (status == 0 || status == 401 || status >= 500) break
            }
        }
        if (api !== client || user?.id != currentUser.id) return
        val remaining = pendingStore.keysWithPrefix(prefix).size + TrainingSync.count(appContext, client, currentUser.id)
        _syncNotice.value = if (remaining == 0) "" else "有 $remaining 条训练记录尚未同步，已保留在此设备上。请联网后重试。"
    }

    fun clearSession() {
        profileRevision=UUID.randomUUID()
        syncGeneration = UUID.randomUUID(); syncTask?.cancel(); syncTask = null
        _syncNotice.value = ""; _syncingRecords.value = false
        room?.stop(); room = null; user = null; lobbyReady = false
        api?.clearCookies()
    }

    fun close() { room?.stop(); syncTask?.cancel(); scope.cancel() }

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
 * 房间状态机：校时、角色选择、发令/冲线、事件补交与 HTTP 房间同步。
 * 所有字段保留可直接读取的 var，同时以 StateFlow 暴露给 Compose。
 */
class RoomModel(
    private val context: Context,
    private val api: APIClient,
    private val user: User,
    val code: String,
    store: PendingStore? = null,
    private val synchronizeTime: suspend ((Int) -> Unit) -> ClockMapping = { cc.runtick.app.core.AppTimeSync.synchronize(it) },
) {

    private val prefs = context.getSharedPreferences("runtick.room", Context.MODE_PRIVATE)
    private val pendingStore: PendingStore = store ?: SharedPreferencesPendingStore(context)
    private val audio = StarterAudio(context)
    private val reactionMotion = ReactionMotion(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val pendingKey = "pending:${api.baseUrl.trimEnd('/')}:${user.id}:$code"
    private val roleSelection = RoleSelection()
    private var eventClock: ClockMapping? = null
    private var recordingClock: ClockMapping? = null
    private var fireGeneration = UUID.randomUUID()
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

    private val _cueDeadline = MutableStateFlow<Double?>(null)
    var cueDeadline: Double?
        get() = _cueDeadline.value
        set(value) { _cueDeadline.value = value }
    val cueDeadlineFlow: StateFlow<Double?> get() = _cueDeadline

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
            _stages.value = value.map { it.normalized() }
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
        if (saved != null && saved.size == 3) {
            _stages.value = saved.map { it.normalized() }
        }
        pending = pendingStore.load(pendingKey)
    }

    /** members 中自己的服务端角色。 */
    val serverRole: String?
        get() = members.firstOrNull { it.loginId == user.loginId }?.deviceRole

    /** 展示角色：乐观值优先。 */
    val role: String?
        get() = roleSelection.effective(serverRole)

    fun displayedRole(member: MemberState): String? {
        val selected = roleSelection.optimistic ?: return member.deviceRole
        return if (member.loginId == user.loginId) selected else if (selected == "start") "finish" else "start"
    }

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

    /** 启动初始校时和串行 HTTP 房间轮询（含自动补交）。 */
    fun start() {
        if (started || stopped) return
        started = true
        scope.launch { calibrate() }
        val manager=context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        var observedNetwork: android.net.Network? = null
        var receivedInitialNetwork = false
        val callback=object: android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { scope.launch {
                val changed = receivedInitialNetwork && observedNetwork != network
                observedNetwork = network; receivedInitialNetwork = true
                if(changed) {
                    needsResync = true
                    if(clock?.fresh != true && !syncing) { timeSync.invalidate(); calibrate() }
                }
            } }
            override fun onLost(network: android.net.Network) { scope.launch {
                if(observedNetwork == network) { observedNetwork = null; needsResync = true }
            } }
        }
        networkCallback=callback
        manager.registerDefaultNetworkCallback(callback)
        scope.launch { pollLoop() }

    }

    /** 接受不回退的 race 更新；比赛结束即复位发令状态。 */
    fun update(value: Race) {
        val current = race
        if (current != null && !current.accepts(value)) return
        if (pending?.let { it.raceId != value.id || !value.active } == true) archivePending()
        if (current?.id != value.id) recordingClock = clock?.takeIf { value.active && it.fresh }
        if (value.active && recordingClock == null && clock?.fresh == true) recordingClock = clock
        if (!value.active) recordingClock = null
        val settings = value.starterSettings
        if (current?.id != value.id && settings != null && settings.size == 3 && settings.all { it.valid }) stages = settings
        race = if (current?.id == value.id && value.reactionStatus == null) value.copy(reactionMs = current.reactionMs, reactionStatus = current.reactionStatus) else value
        if (!value.active) interrupt()
    }

    private fun archivePending() {
        val value = pending ?: return
        pendingStore.save("$pendingKey:${value.raceId}", value)
        pendingStore.deleteIfUnchanged(pendingKey, value)
        pending = null
    }

    val hasRecordingClock: Boolean get() = recordingClock != null || clock?.fresh == true

    /** 停止全部房间协程。 */
    fun stop() {
        networkCallback?.let { callback -> runCatching { (context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).unregisterNetworkCallback(callback) } }
        networkCallback=null
        stopped = true
        scope.cancel()
        connected = false
        audio.stop(); reactionMotion.end(if (race?.status == "completed") "timeout" else "cancelled")
        interrupt()
    }

    /** 退后台：取消按压、停音频、清提示。 */
    fun interrupt() {
        fireGeneration = UUID.randomUUID()
        pressed = false
        eventClock = null
        audio.stop(); reactionMotion.end(if (race?.status == "completed") "timeout" else "cancelled")
        firing = false
        cue = null
        cueDeadline = null
        _volumeWarning.value = false
    }

    /** 回前台检查有效性；有效映射继续复用，避免同房间恢复反复校时。 */
    fun resume() {
        if (clock?.fresh != true && (race?.active != true || recordingClock == null)) {
            scope.launch { calibrate() }
        }

    }

    /** Native NTP sampling; preserve the last result on failure. */
    private val installationId: String by lazy {
        val prefs=context.getSharedPreferences("runtick.device",Context.MODE_PRIVATE)
        prefs.getString("installationId",null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("installationId",it).apply() }
    }
    private val _syncMetrics=MutableStateFlow<Pair<Double?,Double?>>(null to null)
    val syncMetricsFlow: StateFlow<Pair<Double?,Double?>> get()=_syncMetrics
    val syncTarget get() = cc.runtick.app.core.AppTimeSync.target
    var editingSettings=false
    var allowHTTPFallback = false
    var syncError = ""
    private var needsResync = false
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    val syncSummary: String get() {
        if (syncing) return "正在校准 $progress/$syncTarget"
        val value=recordingClock ?: clock ?: return if(syncError.isEmpty()) "尚未同步时间" else "时间同步失败，请重试"
        if(!value.fresh) return "时间同步已过期，请重新同步"
        return if(syncError.isEmpty()) "时间已同步" else "时间同步质量下降，请重新同步"
    }
    private val timeSync = cc.runtick.app.core.AppTimeSync
    suspend fun calibrate(force: Boolean = false) {
        if (syncing || pressed || firing) return
        if(force) timeSync.invalidate()
        syncing = true
        progress = 0
        try {
            try { clock = synchronizeTime { progress = it; _syncMetrics.value=cc.runtick.app.core.AppTimeSync.latestRTT to cc.runtick.app.core.AppTimeSync.averageRTT; android.util.Log.d("RunTickTimeSync",cc.runtick.app.core.AppTimeSync.detail) }; syncError = ""; needsResync = false }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!allowHTTPFallback) throw e
                clock = httpFallback(); syncError = "主时间源不可用，使用应用 API HTTP 备用源"; needsResync = false
            }
            clock?.let { _syncMetrics.value=(cc.runtick.app.core.AppTimeSync.latestRTT ?: it.avgRttMs) to (cc.runtick.app.core.AppTimeSync.averageRTT ?: it.avgRttMs); android.util.Log.d("RunTickNTP", "server=${it.server} samples=${it.validSamples} RTT=${it.avgRttMs} offset=${it.offsetMs} age=${it.ageMs} estimatedUncertainty=${it.estimatedUncertaintyMs}") }
            if (race?.active == true && recordingClock == null) recordingClock = clock
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (clock == null) clock = timeSync.latest
            syncError = e.message ?: "校时失败"
            android.util.Log.d("RunTickTimeSync", "Calibration failed: $syncError")
            // Calibration status stays inline; it must not enter the global modal error channel.
        } finally {
            syncing = false
        }
    }

    private suspend fun httpFallback(): ClockMapping {
        val samples = mutableListOf<ClockSample>()
        repeat(8) {
            val sent=MonotonicClock.now()
            val response=api.request("/api/clock?clientSentAt=$sent&nonce=${UUID.randomUUID()}") { decodeClock(it) }
            val received=MonotonicClock.now()
            require(response.clientSentAt==sent && response.sentAt>=response.receivedAt && response.processingMs==response.sentAt-response.receivedAt && received-sent>=response.processingMs)
            samples.add(ClockSample(sent,received,response.receivedAt,response.sentAt))
            delay(100)
        }
        val offsets=samples.map { ((it.serverReceivedAt-it.sentAt)+(it.serverSentAt-it.receivedAt))/2 }
        require(offsets.max()-offsets.min()<=10) { "HTTP 降级质量不足" }
        return cc.runtick.app.core.NtpQuality.select(samples).copy(server="HTTP",validSamples=samples.size)
    }

    private val _roleRevision = MutableStateFlow(0)
    val roleRevisionFlow: StateFlow<Int> get() = _roleRevision
    private var attemptedAutoStart: String? = null
    fun pick(deviceRole: String) {
        if (race?.active == true || !connected || busy || stopped) return
        val drain = roleSelection.begin(deviceRole, serverRole)
        _roleRevision.value++
        if (!drain) return
        scope.launch {
            while (roleSelection.inFlight != null && !stopped) {
                val target = roleSelection.inFlight!!
                try {
                    api.request("/api/rooms/$code/role", "POST", buildJsonObject { put("deviceRole", target) }) { }
                    roleSelection.succeeded(serverRole)
                } catch (e: CancellationException) { throw e }
                catch (e: Throwable) {
                    roleSelection.fail()
                    if (!roleSelection.saving) error = e.message ?: "角色切换未完成"
                }
                _roleRevision.value++
            }

        }
    }

    suspend fun saveRoomSettings() {
        api.request("/api/rooms/$code/settings","PUT",buildJsonObject {
            put("starterSettings",AppJson.encodeToJsonElement(ListSerializer(StageDelay.serializer()),stages))
        }) { }
    }
    fun stageText(now: Double = MonotonicClock.now()): String {
        if(race?.startAt!=null) return "计时中"
        val mapping=recordingClock ?: clock ?: return "等待发令"
        val point=race?.cueTimeline?.lastOrNull { it.at<=mapping.utcAt(now) } ?: return "等待发令"
        return listOf("各就各位","预备","枪声")[point.stage.coerceIn(0,2)]
    }
    suspend fun beginRecording() {
        if (pending != null) { error = "上一轮计时记录尚未同步，请重新同步后再开始。"; return }
        if (needsResync || clock?.fresh != true) { if (needsResync) timeSync.invalidate(); calibrate() }
        if (clock?.fresh != true || syncing) return
        if (!ready || !connected || role == null || roleSaving) {
            error = if (roleSaving) "角色正在后台同步，请稍后开始记录。" else "请等待两端就绪并完成校时，再开始记录。"
            return
        }
        raceAction()
    }

    suspend fun autoFire() {
        val current = race ?: return
        if (raceRole != "start" || !current.active || current.startAt != null || attemptedAutoStart == current.id) return
        val settings = current.starterSettings ?: return
        if (settings.size != 3 || settings.any { !it.valid }) { error = incompatiblePlan; return }
        if (clock?.fresh != true) calibrate()
        if (clock?.fresh != true) return
        attemptedAutoStart = current.id
        stages = settings
        fire()
    }

    /** 开始新比赛（action=start）或进入下一轮（action=next）。 */
    suspend fun raceAction(action: String = "start") = withBusy {
        val currentRace = race
        val path = if (action == "start") "/api/rooms/$code/races"
        else "/api/rooms/$code/races/${currentRace?.id ?: throw ClientError(0, null, "当前没有比赛")}/$action"
        val body: JsonObject? = null
        val data = api.request(path, "POST", body) { decodeRace(it) }
        if (action == "start" && data?.starterSettings == null) throw ClientError(0, null, "发令设置暂时未能同步，请返回房间重试。")
        data?.let { update(it) }
    }

    /** 起点发令：校时新鲜且尚未有 start 事件时，播放三段音频并在枪声时刻 capture。 */
    private val _volumeWarning = MutableStateFlow(false)
    val volumeWarningFlow: StateFlow<Boolean> get() = _volumeWarning
    fun dismissVolumeWarning() { _volumeWarning.value = false }

    private val incompatiblePlan = "本轮发令设置超出 0～10 秒整数范围，暂无法发令。请重新创建房间并使用此范围设置。"
    suspend fun fire(allowReducedVolume: Boolean = false) {
        val settings = race?.starterSettings ?: stages
        if (settings.size != 3 || settings.any { !it.valid }) { error = incompatiblePlan; return }
        val snapshot = recordingClock ?: clock
        val pass = snapshot != null && snapshot.fresh && !syncing && !firing &&
            race?.active == true && race?.startAt == null && pending == null && raceRole == "start"
        if (!pass) {
            if (clock?.fresh != true) {
                calibrate()
                // Successful recalibration is reflected by the inline sync status.
            }
            return
        }
        _volumeWarning.value = false
        val clockSnapshot = snapshot!!
        val token = UUID.randomUUID(); fireGeneration = token
        val raceId = race!!.id
        firing = true
        try {
            cue = "请保持手机与起跑器稳定…"
            reactionMotion.prepare(settings[0].reactionEnabled)
            currentCoroutineContext().ensureActive()
            if (fireGeneration != token || stopped || race?.id != raceId || race?.active != true) return
            val clicked = MonotonicClock.now()
            val (gunAt, extra) = audio.fire(clicked, settings, scheduledTimes = race?.cueTimeline?.map { point -> val now=MonotonicClock.now(); now+point.at-clockSnapshot.utcAt(now) }, onCue = { if (fireGeneration == token) cue = it }, onDeadline = { if (fireGeneration == token) cueDeadline = it })
            if (fireGeneration != token || stopped || race?.id != raceId || race?.active != true) return
            capture("start", gunAt, clockSnapshot, extra)
            reactionMotion.gun(gunAt) { result ->
                if (race?.id == raceId) race = race?.copy(reactionMs = result.reactionMs, reactionStatus = result.reactionStatus)
                val path = "/api/rooms/$code/races/$raceId/reaction"
                val body = TrainingSync.reactionBody(result.reactionMs, result.reactionStatus)
                TrainingSync.enqueue(context, api, user.id, path, body, raceId)
                scope.launch {
                    try { TrainingSync.send(context, api, user.id, path, body, raceId) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = "反应时尚未同步，已保留在此设备上。请联网后重试。" }
                }
            }
        } catch (e: CancellationException) {
            if (fireGeneration == token) { audio.stop(); reactionMotion.end() }
            throw e
        } catch (e: Throwable) {
            if (fireGeneration == token) {
                error = e.message ?: "发令未完成，请重试。"
                audio.stop(); reactionMotion.end()
            }
        } finally { if (fireGeneration == token) firing = false }
    }

    /** 终点按下：只锁定校时快照，不产生事件。 */
    fun press() {
        val snapshot = recordingClock ?: clock?.takeIf { it.fresh }
        if (snapshot == null || syncing || pending != null ||
            race?.active != true || race?.finishAt != null || raceRole != "finish"
        ) {
            if (snapshot == null) error = "尚未完成时间校准，请返回房间重新校时。"
            return
        }
        eventClock = snapshot
        pressed = true
    }

    /**
     * 终点松手：capture finish 事件。
     * at 为触控事件自身的本机单调时间，由 capture 换算为服务器时间。
     */
    fun release(at: Double) {
        val snapshot = eventClock ?: return
        pressed = false
        eventClock = null
        if (race?.active != true) return
        capture("finish", at, snapshot)
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
                AppJson.encodeToJsonElement(TimingEvent.serializer(), event).jsonObject,
            ) { decodeRace(it) }
            if (pending == current) pending = null
            pendingStore.deleteIfUnchanged(pendingKey, current)
            if (race?.id == current.raceId) data?.let { update(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (pending != current) return
            error = "计时记录暂未同步，已保留在此设备上。请联网后重试。"
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
            if (race?.id == currentRace.id) update(race!!.copy(savedResult = SavedResult(record.distance, record.isPb, record.pbImprovementMs)))
        }
    }

    // ---------- 内部实现 ----------

    /** One non-overlapping HTTP state request also renews presence. */
    private suspend fun pollLoop() {
        while(!stopped && currentCoroutineContext().isActive) {
            try {
                val state=api.request("/api/rooms/$code/state","POST") { AppJson.decodeFromJsonElement(cc.runtick.app.core.RoomPoll.serializer(),it) }
                connected=true; ready=state.ready; members=state.members
                roleSelection.receivedServerState(serverRole); _roleRevision.value++
                if(state.race!=null) update(state.race) else archivePending()
                if(race?.active!=true && !busy && !editingSettings) stages=state.starterSettings
                if(pending!=null && autoRetry && MonotonicClock.now()>=retryAfter) submit()
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { connected=false; ready=false; android.util.Log.d("RunTickRoom","Poll failed: ${e.message}") }
            delay(if(!connected) 3000 else if(race?.active==true) 500 else 1000)
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
        val serverAt = if (localTime) clockSnapshot.utcAt(at) else at
        val value = PendingEvent(
            code, currentRace.id, user.id,
            TimingEvent(kind, serverAt, clockSnapshot.estimatedUncertaintyMs + extra,
                MonotonicClock.nanosAt(at).toString(), installationId, currentRace.id, clockSnapshot.server,
                clockSnapshot.avgRttMs, clockSnapshot.ageMs, clockSnapshot.offsetMs,
                clockSnapshot.syncSource),
        )
        pending = value
        val diagnostics=context.getSharedPreferences("runtick.eventDiagnostics",Context.MODE_PRIVATE)
        val entries=(diagnostics.getString("events",null)?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) as kotlinx.serialization.json.JsonArray }.getOrNull() }?.toList() ?: emptyList()) + AppJson.encodeToJsonElement(PendingEvent.serializer(),value)
        diagnostics.edit().putString("events",kotlinx.serialization.json.JsonArray(entries.takeLast(200)).toString()).apply()
        pendingStore.save(pendingKey, value)
        scope.launch { submit() }
    }
}
