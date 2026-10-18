package cc.runtick.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Android 网络访问不需要运行时授权；连接探测不代表训练服务一定可用。 */
@Composable
fun NetworkPreparationCard() {
    var retry by remember { mutableIntStateOf(0) }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val client = remember { OkHttpClient.Builder().callTimeout(6, TimeUnit.SECONDS).build() }
    LaunchedEffect(retry) {
        checking = true
        status = try {
            val reachable = withContext(Dispatchers.IO) {
                client.newCall(Request.Builder().url("https://www.apple.com/library/test/success.html").build()).execute().use { it.isSuccessful }
            }
            if (reachable) "已连接 Apple 网站；训练时仍需正常访问 RunTick 服务。" else "暂时无法完成网络检测，请确认网络后重试。"
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { "暂时无法完成网络检测，请确认网络后重试。" }
        finally { checking = false }
    }
    RTCard(title = "网络访问") {
        Text("RunTick 需要联网完成登录、双端协同、时间校准和成绩保存。请允许本应用使用无线局域网与移动网络。Android 通常不会弹出单独的网络授权框，可在系统设置中检查网络限制。", color = RT.muted, fontSize = 13.sp)
        Text(if (checking) "正在检测网络…" else status, color = RT.muted, fontSize = 12.sp)
        RTButton(onClick = { retry++ }, enabled = !checking) { RTButtonInkText("重新检测网络") }
    }
}
