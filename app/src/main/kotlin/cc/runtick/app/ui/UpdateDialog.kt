package cc.runtick.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import cc.runtick.app.services.AppModel

/**
 * App 更新弹窗：强制更新时不可关闭、仅「立即更新」；非强制时可用「稍后」关闭。
 * 无新版本或检查失败时不显示任何内容。
 */
@Composable
fun RTUpdateDialog(model: AppModel) {
    val decision by model.updateFlow.collectAsState()
    val current = decision ?: return
    val context = LocalContext.current
    val info = current.info
    val hasUrl = !info.downloadUrl.isNullOrBlank()

    // 用浏览器打开下载地址；无可用浏览器时静默忽略。
    fun openDownload() {
        val url = info.downloadUrl ?: return
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    AlertDialog(
        onDismissRequest = { if (!current.force) model.dismissUpdate() },
        title = { Text("发现新版本") },
        text = {
            Text(buildString {
                append("新版本 ${info.versionName} 已发布")
                if (!info.notes.isNullOrBlank()) append("\n\n${info.notes}")
            })
        },
        confirmButton = {
            TextButton(enabled = hasUrl, onClick = { openDownload() }) {
                Text(if (current.force) "立即更新" else "去更新")
            }
        },
        dismissButton = if (!current.force) {
            { TextButton(onClick = { model.dismissUpdate() }) { Text("稍后") } }
        } else null
    )
}
