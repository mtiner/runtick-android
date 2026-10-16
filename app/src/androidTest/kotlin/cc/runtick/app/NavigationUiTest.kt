package cc.runtick.app

import android.graphics.Bitmap
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
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(if (request.path?.startsWith("/api/starts") == true)
                    """{"ok":true,"data":{"records":[],"hasMore":false}}"""
                    else """{"ok":true,"data":{"athletes":[],"distances":[],"records":[],"hasMore":false}}""")
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
        compose.onAllNodes(hasProgressBarRangeInfo()).onFirst().performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(6.7f) }
        compose.runOnIdle {
            assertEquals(7.0, saved[0].min, 0.0)
            assertTrue(saved.all { it.valid && it.max <= 10 && it.min == kotlin.math.round(it.min) })
        }
        screenshot("interval-settings-cutout")
    }
}
