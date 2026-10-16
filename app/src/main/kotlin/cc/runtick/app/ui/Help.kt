package cc.runtick.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 帮助步骤文案（与 iOS HelpView 一致）。 */
private val helpSteps = listOf(
    "进入同一房间" to "两台手机分别登录。一人创建房间，另一人输入四位房间号加入；在准备页可切换起点和终点角色。",
    "等待准备完成" to "保持两端在线和 App 在前台，等待自动校时。网络波动时可重新校准时间。",
    "准备发令声音" to "媒体音量调至最大，使用本机扬声器。发令使用媒体通道，可在铃声静音模式下播放。",
    "开始这一组" to "任一端点击「开始记录」，确认发令设置后双端进入记录页；起点自动播放发令，终点提前按住触控区，听到枪声起跑。",
    "冲线时松手" to "终点在运动员冲线时松手。触控取消或切换到后台不会记录成绩；提交失败时保留原始时间戳并重试。",
    "保存并继续" to "终点填写距离并保存，成绩归入起点运动员名下。保存后点击「下一组」。PB 表示该距离当前个人最好成绩。",
    "查看历史与退出" to "在「我的」中查看历史成绩，可筛选距离、排序和翻页。退出房间保留登录和成绩；退出登录会清除本机登录状态。"
)

/** 使用帮助内容：品牌页头 + 7 步说明 + 权限检测入口。 */
@Composable
fun RTHelpContent(onGuide: () -> Unit, modifier: Modifier = Modifier) {
    RTPage(modifier = modifier) {
        RTBrand(showHelp = false)
        RTHero(badge = "使用帮助", title = "RunTick 使用说明", subtitle = "从准备到冲线，让每一步都清楚。")
        RTCard {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                helpSteps.forEachIndexed { index, (title, body) ->
                    Row(Modifier.padding(vertical = 5.dp)) {
                        // 序号圆圈：aqua 20% 底。
                        Box(
                            Modifier
                                .size(28.dp)
                                .background(RT.aqua.copy(alpha = 0.2f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("${index + 1}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = RT.ink)
                        }
                        Column(
                            Modifier.padding(start = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = RT.ink)
                            Text(body, fontSize = 14.sp, lineHeight = 20.sp, color = RT.muted)
                        }
                    }
                }
            }
        }
        Text(
            "前往权限检测页 →",
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onGuide)
                .padding(vertical = 10.dp),
            fontSize = 14.sp,
            color = RT.deep,
            textDecoration = TextDecoration.Underline,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        RTBrandFooter()
    }
}

/** 全局帮助弹层（底部面板）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RTGlobalHelpSheet(onDismiss: () -> Unit, onGuide: () -> Unit = {}) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = RT.background) {
        RTPage { RTHelpContent(onGuide = { onDismiss(); onGuide() }) }
    }
}
