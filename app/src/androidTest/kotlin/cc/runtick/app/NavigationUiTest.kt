package cc.runtick.app

import android.graphics.Bitmap
import android.content.Context
import android.media.AudioManager
import cc.runtick.app.services.RoomModel
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
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import cc.runtick.app.core.*
import cc.runtick.app.network.APIClient
import cc.runtick.app.ui.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

class NavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = MockWebServer()
    @Before fun setup() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "POST" && request.path == "/api/rooms/1234/races/auto-plan/events") {
                    val at = AppJson.parseToJsonElement(request.body.readUtf8()).jsonObject["at"]!!
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(
                        """{"ok":true,"data":{"race":{"id":"auto-plan","status":"running","createdAt":1,"startUserId":2,"finishUserId":1,"startAt":$at}}}""")
                }
                if (request.method == "POST" && request.path == "/api/rooms/1234/races") {
                    val settings = AppJson.parseToJsonElement(request.body.readUtf8()).jsonObject["starterSettings"]!!
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
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
    }
    @After fun cleanup() { server.shutdown() }
    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
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
        pressBack()
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
        pressBack()
        compose.onAllNodesWithText("历史成绩").onFirst().assertIsDisplayed()
        pressBack()
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
            compose.onNodeWithText("已确认声音，开始记录").performScrollTo().performClick()
            compose.onNodeWithText("按住屏幕，等待冲线").assertIsDisplayed()
            screenshot("finish-recording-cutout")
            pressBack()
            compose.onNodeWithText("按住屏幕，等待冲线").assertDoesNotExist()
        } finally { compose.runOnIdle { model.stop() } }
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

}
