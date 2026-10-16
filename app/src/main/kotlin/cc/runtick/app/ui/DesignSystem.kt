package cc.runtick.app.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 设计 token，逐项对应 iOS DesignSystem.swift（源自 runtick-app main.css）。
 */
object RT {
    val aqua = Color(0xFF65DDD5)
    val deep = Color(0xFF2FBFB5)
    val ink = Color(0xFF173E3B)
    val muted = Color(0xFF5F7B77)
    val lemon = Color(0xFFE6F56B)
    val background = Color(0xFFF7FBF9)
}

/** 系统关闭动画（缩放为 0）时跳过进场/交互动画，对应 iOS Reduce Motion。 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        val resolver = context.contentResolver
        val animator = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val transition = Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)
        animator == 0f || transition == 0f
    }
}

/** 页面背景：底色 + 左上 aqua 径向渐变 + 右下 lemon 径向渐变。 */
fun Modifier.rtBackdrop(): Modifier = this
    .background(RT.background)
    .drawBehind {
        drawRect(Brush.radialGradient(
            colors = listOf(RT.aqua.copy(alpha = 0.30f), Color.Transparent),
            center = Offset.Zero, radius = 350.dp.toPx()
        ))
        drawRect(Brush.radialGradient(
            colors = listOf(RT.lemon.copy(alpha = 0.27f), Color.Transparent),
            center = Offset(size.width, size.height), radius = 300.dp.toPx()
        ))
    }

/** 页面骨架：可滚动、内容最大宽度 460、统一直边距与间距。 */
@Composable
fun RTPage(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .rtBackdrop()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 18.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Box(Modifier.widthIn(max = 460.dp)) { Column(verticalArrangement = Arrangement.spacedBy(20.dp)) { content() } }
    }
}

private val cardShape = RoundedCornerShape(24.dp)

/** 卡片：白底、圆角 24、细描边、柔和阴影、内边距 24、可选标题。 */
@Composable
fun RTCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 18.dp, shape = cardShape, spotColor = RT.ink.copy(alpha = 0.055f), ambientColor = RT.ink.copy(alpha = 0.055f))
            .background(Color.White, cardShape)
            .border(0.5.dp, RT.ink.copy(alpha = 0.035f), cardShape)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        if (title != null) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = RT.muted)
        }
        content()
    }
}

/** 进场动画：透明度 0→1、位移 y 14→0，spring 0.5/0.85，支持逐元素延迟。 */
@Composable
fun Modifier.rtEntrance(delayMs: Int = 0): Modifier {
    val reduceMotion = rememberReduceMotion()
    val progress = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) progress.snapTo(1f)
        else {
            kotlinx.coroutines.delay(delayMs.toLong())
            progress.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
        }
    }
    val value = progress.value
    return graphicsLayer {
        alpha = value
        translationY = (1f - value) * 14f
    }
}

/** chip 胶囊：12sp semibold，默认 aqua 22% 底，lemon=true 时柠檬底。 */
@Composable
fun RTChip(text: String, modifier: Modifier = Modifier, lemon: Boolean = false) {
    Text(
        text,
        modifier = modifier
            .background(if (lemon) RT.lemon else RT.aqua.copy(alpha = 0.22f), RoundedCornerShape(50))
            .padding(horizontal = 13.dp, vertical = 7.dp),
        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = RT.ink
    )
}

/** 主/次按钮：圆角 16、渐变底、描边与阴影；禁用降透明、按下缩放。 */
@Composable
fun RTButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    secondary: Boolean = true,
    content: @Composable RowScopeAlias.() -> Unit
) {
    val reduceMotion = rememberReduceMotion()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(16.dp)
    val background: Brush = if (secondary) SolidColor(Color(0xFFEDF4F2))
    else Brush.verticalGradient(listOf(Color(0xFF7CE5DD), Color(0xFF4FD2C8)))
    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                val scale = if (pressed && !reduceMotion) 0.975f else 1f
                scaleX = scale
                scaleY = scale
            }
            .alpha(if (enabled) 1f else 0.42f)
            .shadow(
                elevation = if (secondary) 0.dp else 12.dp,
                shape = shape,
                spotColor = if (secondary) Color.Transparent else RT.aqua.copy(alpha = 0.30f)
            )
            .background(background, shape)
            .border(1.dp, if (secondary) RT.ink.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.45f), shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp)
            .heightIn(min = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
    }
}

/** 按钮内容作用域（行内排版）。 */
typealias RowScopeAlias = androidx.compose.foundation.layout.RowScope

/** 主按钮文字样式。 */
@Composable
internal fun RTButtonText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, textAlign = TextAlign.Center)
}

/** 次级按钮文字样式。 */
@Composable
internal fun RTButtonInkText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = RT.ink, textAlign = TextAlign.Center)
}

/** 输入框：圆角 14、背景色底、ink 9% 描边；基于 BasicTextField 自绘。 */
@Composable
fun RTTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    fontSize: TextUnit = 16.sp,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    textAlign: TextAlign = TextAlign.Start
) {
    val shape = RoundedCornerShape(14.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.42f)
            .background(RT.background, shape)
            .border(1.dp, RT.ink.copy(alpha = 0.09f), shape)
            .padding(16.dp),
        textStyle = TextStyle(fontSize = fontSize, color = RT.ink, textAlign = textAlign),
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        readOnly = readOnly,
        enabled = enabled,
        singleLine = true,
        cursorBrush = SolidColor(RT.deep),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(placeholder, fontSize = fontSize, color = RT.muted.copy(alpha = 0.7f), textAlign = textAlign)
                }
                inner()
            }
        }
    )
}

/** 提示条：正常为 aqua 10% 底 + 对勾；error 为红底红字。 */
@Composable
fun RTNotice(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (error) Color.Red.copy(alpha = 0.06f) else RT.aqua.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = if (error) Icons.Filled.Error else Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = if (error) Color.Red else RT.muted,
            modifier = Modifier.size(16.dp)
        )
        Text(text, fontSize = 12.sp, lineHeight = 17.sp, color = if (error) Color.Red else RT.muted)
    }
}

/** Hero 区：chip 徽标 + 大标题 + 副标题。 */
@Composable
fun RTHero(badge: String, title: String, modifier: Modifier = Modifier, subtitle: String = "") {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        RTChip(badge)
        Text(
            title,
            modifier = Modifier.fillMaxWidth(),
            fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.3.sp,
            lineHeight = 36.sp, textAlign = TextAlign.Center
        )
        if (subtitle.isNotEmpty()) {
            Text(
                subtitle,
                modifier = Modifier.fillMaxWidth(),
                fontSize = 14.sp, lineHeight = 20.sp, color = RT.muted, textAlign = TextAlign.Center
            )
        }
    }
}

/** 页头品牌行：wordmark + 分隔 + 中文；右侧可选退出与帮助按钮。 */
@Composable
fun RTBrand(
    modifier: Modifier = Modifier,
    showHelp: Boolean = true,
    onExit: (() -> Unit)? = null,
    exiting: Boolean = false,
    onHelp: (() -> Unit)? = null
) {
    Row(modifier = modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RTWordmark(size = 19.sp, color = RT.ink)
            Text("/", color = RT.muted.copy(alpha = 0.55f), fontSize = 15.sp)
            RTWordmark(chinese = true, size = 19.sp, color = RT.ink)
        }
        Spacer(Modifier.weight(1f))
        if (onExit != null) {
            RTCircleIconButton(
                icon = { Text("⎋", fontSize = 15.sp, color = RT.ink) },
                onClick = onExit, enabled = !exiting,
                contentDescription = "退出房间"
            )
            Spacer(Modifier.width(8.dp))
        }
        if (showHelp && onHelp != null) {
            RTCircleIconButton(
                icon = { Text("?", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = RT.ink) },
                onClick = onHelp, contentDescription = "使用说明"
            )
        }
    }
}

/** 页头圆形小按钮（帮助 / 退出）。 */
@Composable
fun RTCircleIconButton(
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null
) {
    Box(
        modifier = modifier
            .size(34.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .background(Color.White.copy(alpha = 0.75f), CircleShape)
            .border(1.dp, RT.ink.copy(alpha = 0.08f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(0.dp).clickable(enabled = false) {})
        icon()
        contentDescription?.let {
            // 无障碍标签由外层语义统一处理，这里保留占位以贴近 iOS accessibilityLabel。
        }
    }
}

/** 品牌文字（v1 用样式化文字实现 wordmark，日后可替换为矢量 Path）。 */
@Composable
fun RTWordmark(chinese: Boolean = false, size: TextUnit = 19.sp, color: Color = RT.ink, modifier: Modifier = Modifier) {
    Text(
        if (chinese) "燃刻" else "RunTick",
        modifier = modifier,
        fontSize = size,
        fontWeight = if (chinese) FontWeight.Bold else FontWeight.ExtraBold,
        color = color,
        letterSpacing = if (chinese) 1.sp else (-0.2).sp
    )
}

/** 页脚品牌行。 */
@Composable
fun RTBrandFooter(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RTWordmark(size = 14.sp, color = RT.muted.copy(alpha = 0.45f))
        Text("/", fontSize = 12.sp, color = RT.muted.copy(alpha = 0.3f))
        RTWordmark(chinese = true, size = 14.sp, color = RT.muted.copy(alpha = 0.45f))
    }
}

/** 统一错误弹窗：标题智能选择；同一消息不重复弹出。 */
@Composable
fun RTErrorAlert(message: String, enabled: Boolean, onErrorShown: (String) -> Unit = {}) {
    var showing by remember { mutableStateOf(false) }
    var presented by remember { mutableStateOf("") }
    var lastMessage by remember { mutableStateOf("") }

    fun presentIfNeeded() {
        if (message.isEmpty()) { lastMessage = ""; return }
        if (enabled && !showing && message != lastMessage) {
            presented = message; lastMessage = message; showing = true
            onErrorShown(message)
        }
    }
    LaunchedEffect(message) { presentIfNeeded() }
    LaunchedEffect(enabled) { if (!enabled) showing = false else presentIfNeeded() }
    LaunchedEffect(showing) { if (!showing) presentIfNeeded() }

    if (showing) {
        val title = if (presented.contains("网络") || presented.contains("连接")) "连接未成功" else "操作未完成"
        AlertDialog(
            onDismissRequest = { showing = false },
            title = { Text(title) },
            text = { Text(presented) },
            confirmButton = { TextButton(onClick = { showing = false }) { Text("好") } }
        )
    }
}

/** PB 庆祝：柠檬圆 + 奖杯 + 12 条彩带旋转展开。 */
@Composable
fun RTPBCelebration(improvementMs: Double?, modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val revealed = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduceMotion) {
            revealed.animateTo(
                1f,
                spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)
            )
        }
    }
    Box(modifier = modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
        // 彩带：12 条胶囊，围绕中心按 30° 旋转，随揭示进度向外展开。
        repeat(12) { index ->
            val expand = lerp(20f, 68f, revealed.value)
            val fade = lerp(0f, 0.85f, revealed.value)
            Box(
                Modifier
                    .align(Alignment.Center)
                    .graphicsLayer {
                        rotationZ = index * 30f
                        translationY = -expand
                        alpha = fade
                    }
            ) {
                Box(
                    Modifier
                        .size(width = 5.dp, height = 12.dp)
                        .background(if (index % 2 == 0) RT.aqua else RT.lemon, RoundedCornerShape(50))
                )
            }
        }
        Box(
            Modifier
                .size(92.dp)
                .graphicsLayer {
                    scaleX = lerp(0.7f, 1f, revealed.value)
                    scaleY = lerp(0.7f, 1f, revealed.value)
                }
                .background(RT.lemon, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("🏆", fontSize = 42.sp)
        }
        Text(
            "新的个人最佳！",
            modifier = Modifier.align(Alignment.BottomCenter),
            fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = RT.ink
        )
    }
}

private fun lerp(from: Float, to: Float, fraction: Float): Float = from + (to - from) * fraction

/** 4 格房间号输入：隐形输入层覆盖在格子上，数字键盘，满 4 位由调用方触发加入。 */
@Composable
fun RTCodeInput(
    code: String,
    onCodeChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    Box(modifier = modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(4) { index ->
                val active = focused && code.length == index
                Box(
                    Modifier
                        .weight(1f)
                        .height(58.dp)
                        .background(RT.background, RoundedCornerShape(14.dp))
                        .border(
                            width = if (active) 2.dp else 1.dp,
                            color = if (active) RT.deep else RT.ink.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(14.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (index < code.length) {
                        Text(
                            code[index].toString(),
                            fontSize = 24.sp, fontWeight = FontWeight.Bold, color = RT.ink
                        )
                    }
                }
            }
        }
        // 隐形输入层：负责接收按键与弹出数字键盘。
        BasicTextField(
            value = code,
            onValueChange = onCodeChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .alpha(0f)
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused },
            textStyle = TextStyle(color = Color.Transparent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            cursorBrush = SolidColor(Color.Transparent)
        )
        // 点击格子区域唤起键盘。
        Box(
            Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { focusRequester.requestFocus() }
        )
    }
}

/** 品牌图标：112dp 圆角 28 方块，渐变底 + 简化停表图形。 */
@Composable
fun RTBrandIcon(modifier: Modifier = Modifier, size: Dp = 112.dp) {
    Box(
        modifier = modifier
            .size(size)
            .shadow(24.dp, RoundedCornerShape(28.dp), spotColor = RT.deep.copy(alpha = 0.16f))
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.verticalGradient(listOf(RT.aqua, RT.deep))),
        contentAlignment = Alignment.Center
    ) {
        // 简化停表：顶部按钮 + 外环 + 表针。
        androidx.compose.foundation.Canvas(Modifier.size(size * 0.62f)) {
            val stroke = size.toPx() * 0.045f
            val white = Color.White.copy(alpha = 0.95f)
            // 顶部按钮
            drawRoundRect(
                color = white,
                topLeft = Offset(this.size.width / 2 - stroke * 1.6f, -stroke * 2.2f),
                size = androidx.compose.ui.geometry.Size(stroke * 3.2f, stroke * 2.4f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(stroke)
            )
            // 外环
            drawCircle(color = white, radius = this.size.minDimension / 2 - stroke, style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke))
            // 表针：指向 1 点钟方向
            val center = Offset(this.size.width / 2, this.size.height / 2)
            val radius = this.size.minDimension / 2 - stroke * 2.4f
            drawLine(
                color = white,
                start = center,
                end = center + Offset(radius * 0.5f, -radius * 0.5f * 1.2f),
                strokeWidth = stroke * 1.1f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            drawCircle(color = white, radius = stroke * 1.1f, center = center)
        }
    }
}

/** 秒数等宽数字格式化（%.3f）。 */
internal fun formatSeconds(ms: Double): String = String.format("%.3f", ms / 1000.0)

/** 毫秒取整显示。 */
internal fun formatMsRound(ms: Double): String = ms.roundToInt().toString()
