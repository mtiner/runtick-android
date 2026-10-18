package cc.runtick.app.ui

import cc.runtick.app.services.AudioReadiness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay

/**
 * 声音准备卡：媒体音量、输出路由与扬声器提示。
 * Android 端 AudioReadiness 为静态读取接口，这里在页面可见期间轮询刷新。
 */
@Composable
fun AudioPreparationCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var volumePercent by remember { mutableIntStateOf(-1) }
    var route by remember { mutableStateOf("") }
    var builtinSpeakerOnly by remember { mutableStateOf(true) }
    var readError by remember { mutableStateOf("") }

    fun refresh() {
        try {
            volumePercent = AudioReadiness.volumePercent(context)
            route = AudioReadiness.outputRouteName(context) ?: "未知输出"
            builtinSpeakerOnly = AudioReadiness.isBuiltInSpeakerOnly(context)
            readError = ""
        } catch (e: Exception) {
            readError = "无法准备发令声音，请稍后重试或重新打开 App。"
        }
    }

    // 进入页面与回到前台时刷新一次；可见期间轮询以跟踪音量键变化。
    LaunchedEffect(Unit) {
        refresh()
        while (true) {
            delay(500)
            refresh()
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    RTCard(title = "声音准备", modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = when {
                    volumePercent == 0 -> Icons.Filled.VolumeMute
                    volumePercent < 100 -> Icons.Filled.VolumeDown
                    else -> Icons.Filled.VolumeUp
                },
                contentDescription = null,
                tint = RT.deep
            )
            Spacer(Modifier.padding(start = 8.dp))
            Text("媒体音量", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = RT.ink)
            Spacer(Modifier.weight(1f))
            if (volumePercent >= 0) {
                Text("$volumePercent%", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = RT.ink)
            }
        }
        if (volumePercent in 0..99) {
            RTNotice(
                if (volumePercent == 0) "当前媒体音量为零，请调至最大，以免听不到枪声。"
                else "建议将媒体音量调至最大，以便清楚听到发令；也可以按当前音量继续。"
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("可用输出设备", fontSize = 14.sp, color = RT.ink)
            Spacer(Modifier.weight(1f))
            Text(route, fontSize = 14.sp, color = RT.muted, textAlign = TextAlign.End)
        }
        if (!builtinSpeakerOnly) {
            RTNotice("检测到外接声音设备。建议断开耳机、蓝牙或外接音响，使用本机扬声器发令，减少输出延迟。", error = true)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.VolumeOff, contentDescription = null, tint = RT.muted, modifier = Modifier.padding(0.dp))
            Text("发令可在静音模式下播放", fontSize = 14.sp, color = RT.ink)
        }
        Text(
            "App 使用媒体播放通道，不受铃声静音开关影响。请用音量键调整媒体音量。",
            fontSize = 12.sp, lineHeight = 17.sp, color = RT.muted
        )
        if (readError.isNotEmpty()) RTNotice(readError, error = true)
    }
}
