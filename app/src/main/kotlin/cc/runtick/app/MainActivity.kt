package cc.runtick.app

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cc.runtick.app.services.AppModel
import cc.runtick.app.ui.LaunchContainer
import cc.runtick.app.ui.RTUpdateDialog
import cc.runtick.app.ui.RT
import cc.runtick.app.ui.RunTickTheme
import cc.runtick.app.ui.RootView

/** AppModel 的 ViewModel 容器：随 Activity 重建保留会话状态。 */
class AppHolder(appContext: Context) : ViewModel() {
    val model = AppModel(appContext)
}

/**
 * 单 Activity 入口：竖屏（Manifest）、锁浅色、房间内自动常亮。
 * 全部 UI 由 Compose 承载，品牌页（LaunchContainer）包住根视图。
 */
class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        // 强制浅色：覆盖系统暗色模式，保证薄荷绿主题稳定。
        val config = Configuration(newBase.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 浅色系统栏（透明背景、深色前景图标），与锁定的浅色主题一致。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
            )
        )

        val factory = viewModelFactory {
            initializer { AppHolder(applicationContext) }
        }

        setContent {
            // viewModel 委托须在组合上下文中获取。
            val holder = viewModel<AppHolder>(factory = factory)
            val model = holder.model

            // 锁浅色：背景由 RTBackdrop 提供，Surface 仅作底色。
            RunTickTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = RT.background) {
                    KeepScreenOnWhileInRoom(model)
                    LaunchContainer(model = model) {
                        RootView(model = model)
                    }
                    // App 更新弹窗：全局置顶，引导页之上也可见。
                    RTUpdateDialog(model = model)
                }
            }
        }
    }

    /** 房间存在时保持屏幕常亮；离开房间清除。 */
    @Composable
    private fun KeepScreenOnWhileInRoom(model: AppModel) {
        val room by model.roomFlow.collectAsState()
        val inRoom = room != null
        DisposableEffect(inRoom) {
            if (inRoom) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }
}
