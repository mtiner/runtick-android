package cc.runtick.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async

/**
 * 启动容器：每次进程启动显示品牌页，并行执行会话恢复；1300ms 后淡出（350ms）。
 * 仅从后台返回不重新显示。
 */
@Composable
fun LaunchContainer(model: cc.runtick.app.services.AppModel, content: @Composable () -> Unit) {
    var launching by remember { mutableStateOf(true) }
    val reduceMotion = rememberReduceMotion()
    // 淡出透明度：launching 时 1，结束后降为 0。
    val launchAlpha by animateFloatAsState(
        targetValue = if (launching) 1f else 0f,
        animationSpec = tween(durationMillis = if (reduceMotion) 0 else 350, easing = androidx.compose.animation.core.EaseOut),
        label = "launchAlpha"
    )

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (launching) 0f else 1f }) {
            content()
        }
        if (launchAlpha > 0.01f) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = launchAlpha }) {
                BrandLaunchView()
            }
        }
    }

    LaunchedEffect(Unit) {
        // 并行：会话恢复与品牌页计时互不阻塞。
        val restoration = async { model.restore() }
        kotlinx.coroutines.delay(1300)
        launching = false
        restoration.await()
    }
}

/** 品牌启动页：白底 backdrop + 品牌图标 + 双语 wordmark + 标语。 */
@Composable
fun BrandLaunchView(modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    var appeared by remember { mutableStateOf(false) }
    val appear by animateFloatAsState(
        targetValue = if (appeared || reduceMotion) 1f else 0f,
        animationSpec = tween(durationMillis = if (reduceMotion) 0 else 550, easing = androidx.compose.animation.core.EaseOut),
        label = "appear"
    )

    LaunchedEffect(Unit) { appeared = true }

    Box(modifier = modifier.fillMaxSize().rtBackdrop(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp)
                .graphicsLayer {
                    alpha = appear
                    scaleX = 0.97f + 0.03f * appear
                    scaleY = 0.97f + 0.03f * appear
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            RTBrandIcon()
            Column(
                modifier = Modifier.padding(top = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RTWordmark(size = 30.sp, color = RT.ink)
                RTWordmark(chinese = true, size = 26.sp, color = RT.ink)
            }
            Text(
                "为每一次认真训练而生",
                modifier = Modifier.padding(top = 28.dp),
                fontSize = 13.sp,
                letterSpacing = 1.5.sp,
                color = Color(0xFF94A69E),
                textAlign = TextAlign.Center
            )
        }
    }
}
