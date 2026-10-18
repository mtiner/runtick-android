package cc.runtick.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.runtick.app.core.*
import cc.runtick.app.network.APIClient
import cc.runtick.app.services.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AppFlowRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<String>()
    private var currentRoom: String? = null
    private var delayEvents = false
    private var failEvents = false
    private val store = MemoryPendingStore()
    private lateinit var api: APIClient
    private fun response(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    @Before fun setup() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty(); requests.add("${request.method} $path")
                return when {
                    path == "/api/auth/me" || path == "/api/auth/login" -> response("""{"ok":true,"data":{"user":{"id":71,"loginId":"runner"}}}""")
                    path == "/api/rooms/current" -> response("""{"ok":true,"data":{"room":${currentRoom?.let { "{\"code\":\"$it\",\"status\":\"active\"}" } ?: "null"}}}""")
                    path.contains("/clock") -> response("""{"ok":false,"error":{"code":"UNAVAILABLE"}}""", 503)
                    path.contains("/events") && (failEvents || path.contains("obsolete")) -> response("""{"ok":false,"error":{"code":"FORBIDDEN"}}""", 403)
                    path.contains("/events") -> response("""{"ok":true,"data":{"race":null}}""").also { if (delayEvents) it.setHeadersDelay(1, TimeUnit.SECONDS) }
                    else -> response("""{"ok":true,"data":{"race":null,"members":[]}}""")
                }
            }
        }
        server.start(); api = APIClient(server.url("/").toString())
    }
    @After fun cleanup() { server.shutdown() }
    private fun event(id: String) = PendingEvent("1234", id, 71, TimingEvent("finish", 1000.0, 2.0))
    private fun race(id: String, status: String = "ready", settings: List<StageDelay>? = null) = Race(id, status, 20.0, startUserId = 72, finishUserId = 71, starterSettings = settings)
    private fun key() = "pending:${api.baseUrl.trimEnd('/')}:71:1234"
    private suspend fun main(block: suspend CoroutineScope.() -> Unit) = withContext(Dispatchers.Main, block)

    @Test fun restoreKeepsExistingRoomWithoutLeaving() = runBlocking { main {
        currentRoom = "1234"
        val app = AppModel(context, store, api)
        try { app.restore(); assertEquals("1234", app.room?.code); assertFalse(app.lobbyReady); assertFalse(requests.any { it.contains("/leave") }) }
        finally { app.clearSession(); app.close() }
    } }
    @Test fun loginWithNoRoomOpensLobbyWithoutLeaving() = runBlocking { main {
        val app = AppModel(context, store, api)
        try { app.login("runner", "letters123"); assertTrue(app.lobbyReady); assertNull(app.room); assertFalse(requests.any { it.contains("/leave") }) }
        finally { app.clearSession(); app.close() }
    } }
    @Test fun obsoleteRecordDoesNotBlockOtherRecordsOrLobby() = runBlocking { main {
        val app = AppModel(context, store, api)
        try {
            app.login("runner", "password"); delay(100)
            store.save(key(), event("obsolete")); store.save(key() + ":valid", event("valid"))
            app.replayPending()
            assertTrue(app.lobbyReady); assertNotNull(store.load(key())); assertNull(store.load(key() + ":valid"))
        } finally { app.clearSession(); app.close() }
    } }
    @Test fun explicitLeavePreservesUnsentRecord() = runBlocking { main {
        val app = AppModel(context, store, api)
        try {
            app.login("runner", "password"); delay(100)
            store.save(key(), event("obsolete")); app.leave()
            assertTrue(app.lobbyReady); assertNotNull(store.load(key())); assertTrue(requests.any { it == "POST /api/rooms/leave" })
        } finally { app.clearSession(); app.close() }
    } }
    @Test fun frozenClockAndCancelledTouchNeverProduceWrongTimestamp() = runBlocking { main {
        failEvents = true
        val model = RoomModel(context, api, User(71, "runner"), "1234", store)
        try {
            model.clock = ClockMapping(13.0, 2.0, 2.0, MonotonicClock.now()); model.update(race("round"))
            model.clock = ClockMapping(900.0, 2.0, 2.0, MonotonicClock.now() - 121000)
            assertTrue(model.hasRecordingClock)
            model.press(); model.cancelPress(); model.release(1000.0); assertNull(model.pending)
            model.press(); model.release(1000.0); assertEquals(1013.0, model.pending!!.event.at, 0.001)
        } finally { model.stop() }
    } }
    @Test fun previousAndCancelledRoundsAreArchived() = runBlocking { main {
        val model = RoomModel(context, api, User(71, "runner"), "1234", store)
        try {
            model.pending = event("old"); store.save(key(), model.pending!!)
            model.update(race("new")); assertNull(model.pending); assertEquals(event("old"), store.load(key() + ":old")); assertNull(store.load(key()))
            model.pending = event("new"); store.save(key(), model.pending!!)
            model.update(race("new", "cancelled")); assertNull(model.pending); assertEquals(event("new"), store.load(key() + ":new"))
        } finally { model.stop() }
    } }
    @Test fun lateSubmissionCannotDeleteNewRoundPending() = runBlocking { main {
        delayEvents = true
        val model = RoomModel(context, api, User(71, "runner"), "1234", store)
        try {
            model.update(race("old")); model.pending = event("old"); store.save(key(), model.pending!!)
            val task = launch { model.submit() }
            withTimeout(5000) { while (!requests.any { it.contains("/old/events") }) delay(10) }
            model.update(race("new")); model.pending = event("new"); store.save(key(), model.pending!!)
            task.join()
            assertEquals(event("new"), model.pending); assertEquals(event("new"), store.load(key()))
        } finally { model.stop() }
    } }
    @Test fun pendingRecordBlocksNewRoundAndFailedCalibrationKeepsError() = runBlocking { main {
        val model = RoomModel(context, api, User(71, "runner"), "1234", store)
        try {
            model.pending = event("old"); model.beginRecording(); assertTrue(model.error.contains("尚未同步")); assertFalse(requests.any { it == "POST /api/rooms/1234/races" })
            model.pending = null; model.fire(); assertTrue(model.error.contains("服务暂时不可用")); assertFalse(model.error.contains("已完成"))
        } finally { model.stop() }
    } }
    @Test fun incompatibleRemoteSettingsAreRejectedWithoutRequests() = runBlocking { main {
        val model = RoomModel(context, api, User(71, "runner"), "1234", store)
        try { model.race = race("remote", settings = listOf(StageDelay(false, 30.0, 30.0))); model.fire(true); assertTrue(model.error.contains("0～10")); assertTrue(requests.isEmpty()) }
        finally { model.stop() }
    } }
    @Test fun malformedDataHasReadableErrorInsteadOfDecoderDetails() = runBlocking { main {
        try {
            api.request("/malformed-user") { AppJson.decodeFromJsonElement(User.serializer(), it) }
            fail("Malformed response must fail")
        } catch (e: ClientError) { assertEquals("暂时无法读取服务器响应，请稍后重试。", e.message) }
    } }

}
