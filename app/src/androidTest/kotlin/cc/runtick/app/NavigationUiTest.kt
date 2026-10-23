package cc.runtick.app

import android.content.ContentValues
import android.provider.MediaStore
import android.graphics.Bitmap
import android.content.Context
import android.media.AudioManager
import cc.runtick.app.services.RoomModel
import kotlinx.coroutines.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import cc.runtick.app.core.*
import cc.runtick.app.network.APIClient
import cc.runtick.app.ui.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

class NavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = MockWebServer()
    @Before fun setup() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/slow-response") {
                    return MockResponse().setHeader("Content-Type", "application/json")
                        .setBody("""{"ok":true,"data":{"probe":true}}""").setBodyDelay(2, TimeUnit.SECONDS)
                }
                if (request.method == "POST" && request.path == "/api/rooms/1234/role") {
                    return MockResponse().setHeader("Content-Type", "application/json")
                        .setBody("""{"ok":true,"data":{}}""").setHeadersDelay(2, TimeUnit.SECONDS)
                }
                if (request.method == "POST" && request.path == "/api/rooms/1234/races/auto-plan/events") {
                    val at = AppJson.parseToJsonElement(request.body.readUtf8()).jsonObject["at"]!!
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(
                        """{"ok":true,"data":{"race":{"id":"auto-plan","status":"running","createdAt":1,"startUserId":2,"finishUserId":1,"startAt":$at}}}""")
                }
                if (request.method == "POST" && request.path == "/api/rooms/1234/races") {
                    val settings = StageDelay.defaults().let { AppJson.encodeToJsonElement(ListSerializer(StageDelay.serializer()),it) }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(
                        """{"ok":true,"data":{"race":{"id":"ui-test-race","status":"ready","createdAt":1,"startUserId":2,"finishUserId":1,"starterSettings":$settings}}}""")
                }
                return MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(if (request.path?.startsWith("/api/starts") == true)
                    """{"ok":true,"data":{"records":[],"hasMore":false}}"""
                    else """{"ok":true,"data":{"athletes":[],"distances":[],"records":[],"hasMore":false}}""")
            }
        }
        server.start()
        compose.activityRule.scenario.onActivity {
            it.enableEdgeToEdge()
            // Test hosts do not run MainActivity's screen-awake lifecycle.
            it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            it.setShowWhenLocked(true); it.setTurnScreenOn(true)
        }
    }
    @After fun cleanup() { server.shutdown() }
    private fun pressSystemBack() {
        // Send the actual key to the focused app/dialog window. Espresso's root picker
        // can select the underlying, unfocused activity on Android 15.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }
    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // UTP uninstalls the tested APK, so external app files are removed after tests.
        // Public MediaStore images survive teardown for the CI artifact collector.
        val resolver = instrumentation.targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RunTickQA")
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        requireNotNull(resolver.openOutputStream(uri)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun aboutUsesSystemBackAndAvoidsStatusBarAndCutout() {
        compose.setContent { RunTickTheme { RTSafeArea {
            var about by remember { mutableStateOf(true) }
            if (about) AboutView { about = false } else Text("返回完成")
        } } }
        compose.onNodeWithText("RunTick").assertIsDisplayed()
        val top = compose.onNodeWithText("RunTick").fetchSemanticsNode().boundsInRoot.top
        var safeTop = 0
        compose.runOnIdle { safeTop = compose.activity.window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top }
        assertTrue("Brand must be below status bar/cutout", top >= safeTop)
        screenshot("about-cutout")
        pressSystemBack()
        compose.onNodeWithText("返回完成").assertIsDisplayed()
    }
    @Test fun historyBackReturnsOneLevelAtATime() {
        val api = APIClient(server.url("/").toString().trimEnd('/'))
        compose.setContent { RunTickTheme { RTSafeArea {
            var history by remember { mutableStateOf(true) }
            if (history) HistoryView(api, User(1, "test"), null) { history = false } else Text("我的页面")
        } } }
        compose.onNodeWithText("单人起跑记录").performScrollTo().performClick()
        compose.onNodeWithText("单人起跑记录").assertIsDisplayed()
        screenshot("solo-history-cutout")
        pressSystemBack()
        compose.onAllNodesWithText("历史成绩").onFirst().assertIsDisplayed()
        pressSystemBack()
        compose.onNodeWithText("我的页面").assertIsDisplayed()
    }
    @Test fun intervalSlidersOnlySaveWholeSecondsUpToTen() {
        var saved = StageDelay.defaults()
        compose.setContent { RunTickTheme { RTSafeArea { RTPage {
            var stages by remember { mutableStateOf(saved) }
            StageSettings(stages, onChange = { stages = it; saved = it })
        } } } }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)).onFirst().performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(6.7f) }
        compose.runOnIdle {
            assertEquals(7.0, saved[0].min, 0.0)
            assertTrue(saved.all { it.valid && it.max <= 10 && it.min == kotlin.math.round(it.min) })
        }
        screenshot("interval-settings-cutout")
    }
    @Test fun finishPhoneCanConfirmAndEnterFullScreenRecording() {
        val api = APIClient(server.url("/").toString().trimEnd('/'))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        lateinit var model: RoomModel
        compose.runOnIdle {
            model = RoomModel(context, api, User(1, "test"), "1234")
            model.members = listOf(MemberState("test", "finish", true), MemberState("starter", "start", true))
            model.connected = true; model.ready = true
            model.clock = ClockMapping(0.0, 1.0, 2.0, MonotonicClock.now())
        }
        try {
            compose.setContent { RunTickTheme { RTSafeArea { RoomView(model, api, User(1, "test"), leave = {}) } } }
            compose.onNodeWithText("开始记录").performScrollTo().performClick()
            compose.onNodeWithText("播放并开始").performClick()
            compose.waitUntil(10000) { model.race?.active == true }
            compose.waitUntil(10000) {
                runCatching { compose.onNodeWithText("按住屏幕，等待冲线").assertIsDisplayed(); true }.getOrDefault(false)
            }
            screenshot("finish-recording-cutout")
            pressSystemBack()
            compose.onNodeWithText("按住屏幕，等待冲线").assertDoesNotExist()
            compose.onNodeWithText("进入记录页").performScrollTo().performClick()
            compose.onNodeWithText("按住屏幕，等待冲线").assertIsDisplayed()
        } finally { screenshot("finish-final-state"); compose.runOnIdle { model.stop() } }
    }
    @Test fun startPhoneAutomaticallyPlaysTheConfirmedPlan() {
        val api = APIClient(server.url("/").toString().trimEnd('/'))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        lateinit var model: RoomModel
        compose.runOnIdle {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
            model = RoomModel(context, api, User(2, "starter"), "1234")
            model.clock = ClockMapping(0.0, 1.0, 2.0, MonotonicClock.now())
            model.connected = true; model.ready = true
            model.update(Race("auto-plan", "ready", 1.0, startUserId = 2, finishUserId = 1,
                starterSettings = List(3) { StageDelay(false, 0.0, 0.0) }))
        }
        try {
            compose.setContent { RunTickTheme { RTSafeArea { RoomView(model, api, User(2, "starter"), leave = {}) } } }
            compose.onNodeWithText("返回房间").assertIsDisplayed()
            compose.onNodeWithText("开始发令").assertDoesNotExist()
            compose.waitUntil(15000) { model.pending != null || model.race?.startAt != null }
            screenshot("starter-recording-cutout")
        } finally { compose.runOnIdle { model.stop() } }
    }

    @Test fun roleSyncSurvivesLeavingTheRoomPage() {
        val api = APIClient(server.url("/").toString().trimEnd('/'))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        lateinit var model: RoomModel
        val showRoom = mutableStateOf(true)
        compose.runOnIdle {
            model = RoomModel(context, api, User(1, "test"), "1234")
            model.members = listOf(MemberState("test", "finish", true), MemberState("starter", "start", true))
            model.connected = true
        }
        try {
            compose.setContent { RunTickTheme { RTSafeArea {
                if (showRoom.value) RoomView(model, api, User(1, "test"), leave = {}) else Text("我的测试页")
            } } }
            compose.onNodeWithText("起点").performScrollTo().performClick()
            compose.runOnIdle { assertEquals("start", model.role); assertTrue(model.roleSaving); showRoom.value = false }
            compose.onNodeWithText("我的测试页").assertIsDisplayed()
            compose.waitUntil(10000) { !model.roleSaving }
            compose.runOnIdle { assertEquals("start", model.role) }
        } finally { compose.runOnIdle { model.stop() } }
    }

    @Test fun delayedResponseBodyDoesNotBlockInteractionOrFailOnMainThread() {
        val api = APIClient(server.url("/").toString().trimEnd('/'))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var done = false
        var failure: Throwable? = null
        var clicks = 0
        compose.setContent { RunTickTheme { RTSafeArea {
            RTButton(onClick = { clicks++ }) { Text("响应期间仍可点击") }
        } } }
        try {
            compose.runOnIdle { scope.launch {
                try { api.request("/slow-response") { assertTrue(it.jsonObject["probe"]!!.jsonPrimitive.boolean) } }
                catch (e: Throwable) { failure = e }
                finally { done = true }
            } }
            compose.onNodeWithText("响应期间仍可点击").performClick()
            compose.runOnIdle { assertEquals(1, clicks); assertFalse(done) }
            compose.waitUntil(10000) { done }
            compose.runOnIdle { assertNull(failure) }
        } finally { scope.cancel() }
    }

    @Test fun largeTextRecordingHeaderDoesNotOverlapAndExitNeedsConfirmation() {
        val api = APIClient(server.url("/").toString())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        lateinit var model: RoomModel
        var exits = 0
        compose.runOnIdle {
            model = RoomModel(context, api, User(2, "starter"), "1234")
            model.clock = ClockMapping(0.0, 1.0, 2.0, MonotonicClock.now())
            model.update(Race("large-text", "ready", 1.0, startUserId = 2, finishUserId = 1))
        }
        try {
            compose.setContent {
                val density = androidx.compose.ui.platform.LocalDensity.current
                CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 2f)) {
                    RunTickTheme { RTSafeArea { RoomView(model, api, User(2, "starter"), leave = { exits++ }) } }
                }
            }
            compose.onNodeWithTag("recording.return").assertIsDisplayed()
            val title = compose.onNodeWithTag("recording.roomLabel").fetchSemanticsNode().boundsInRoot
            val back = compose.onNodeWithTag("recording.return").fetchSemanticsNode().boundsInRoot
            assertFalse("Title must not overlap return button", title.overlaps(back))
            screenshot("recording-large-text")
            compose.onNodeWithTag("recording.return").performClick()
            compose.onNodeWithText("退出房间").performScrollTo().performClick()
            compose.onNodeWithText("退出当前房间？").assertIsDisplayed()
            compose.runOnIdle { assertEquals(0, exits) }
            compose.onNodeWithText("继续训练").performClick()
            compose.onNodeWithText("进入记录页").performScrollTo().performClick()
            compose.onNodeWithTag("recording.return").assertIsDisplayed()
        } finally { compose.runOnIdle { model.stop() } }
    }

    @Test fun profileAvatarPickerSavesFilenameWithSpaces() {
        var avatar: String?=null
        server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if(request.path=="/api/avatars") return MockResponse().setHeader("Content-Type","application/json")
                    .setBody("""{"ok":true,"data":{"avatars":[{"filename":"Nike Dragonfly.png"},{"filename":"Nike Maxfly 2.png"}]}}""")
                if(request.path=="/api/auth/avatar") {
                    assertEquals("PATCH",request.method)
                    avatar=AppJson.parseToJsonElement(request.body.readUtf8()).jsonObject["avatar"]!!.jsonPrimitive.content
                }
                val body=if(request.path?.startsWith("/api/auth/")==true) """{"user":${AppJson.encodeToString(User.serializer(),User(71,"runner",avatar))}}""" else """{"room":null}"""
                return MockResponse().setHeader("Content-Type","application/json").setBody("""{"ok":true,"data":$body}""")
            }
        }
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=cc.runtick.app.services.AppModel(context,suppliedClient=APIClient(server.url("/").toString()))
        runBlocking { withContext(Dispatchers.Main) { app.login("runner","pw") } }
        try {
            compose.setContent { RunTickTheme {
                val user by app.userFlow.collectAsState()
                var picker by remember { mutableStateOf(false) }
                ProfileView(app,user!!,onOpenHistory={},onEditAvatar={picker=true})
                if(picker) AvatarPicker(app,user!!,onDismiss={picker=false})
            } }
            compose.onNodeWithContentDescription("修改头像").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("Nike Dragonfly").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Nike Dragonfly").performClick()
            screenshot("avatar-gallery")
            compose.onNodeWithText("保存头像").performClick()
            compose.waitUntil(10000) { app.user?.avatar=="Nike Dragonfly.png" }
            compose.onNodeWithText("选择头像").assertDoesNotExist()
            assertEquals("Nike Dragonfly.png",avatar)
        } finally { runBlocking { withContext(Dispatchers.Main) { app.clearSession(); app.close() } } }
    }

    @Test fun roomShowsOneSimpleSyncStatusWithoutServerDiagnostics() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val api=APIClient(server.url("/").toString())
        val user=User(71,"runner")
        val room=RoomModel(context,api,user,"1234")
        room.clock=ClockMapping(1.0,2.0,2.0,MonotonicClock.now(),server="f.m.suning.com",validSamples=8)
        try {
            compose.setContent { RunTickTheme { RoomView(room,api,user,leave={}) } }
            compose.onAllNodesWithText("时间已同步").assertCountEquals(1)
            compose.onAllNodesWithText("f.m.suning.com",substring=true).assertCountEquals(0)
            compose.onAllNodesWithText("平均 RTT",substring=true).assertCountEquals(1)
            compose.onAllNodesWithText("Offset",substring=true).assertCountEquals(0)
        } finally { room.stop() }
    }

}
