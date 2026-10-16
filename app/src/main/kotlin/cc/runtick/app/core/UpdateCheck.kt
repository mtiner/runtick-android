package cc.runtick.app.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/** /api/app/version 响应 data 字段的服务端版本信息。 */
@Serializable
data class AppVersionInfo(
    val platform: String,
    val versionName: String,
    val versionCode: Int,
    val minVersionCode: Int = 1,
    val forceUpdate: Boolean = false,
    val notes: String? = null,
    val downloadUrl: String? = null,
    val releasedAt: Long? = null,
)

/** 一次更新检查的判定结果（仅在服务器版本更高时产生）。 */
data class UpdateDecision(
    val info: AppVersionInfo,
    val currentVersionCode: Int,
    val force: Boolean,
)

/** 更新判定：服务器版本更高才有新版本；强制更新 = 服务器标记或当前版本低于最低可用版本。 */
fun evaluateUpdate(currentVersionCode: Int, info: AppVersionInfo): UpdateDecision? {
    if (info.versionCode <= currentVersionCode) return null
    val force = info.forceUpdate || currentVersionCode < info.minVersionCode
    return UpdateDecision(info, currentVersionCode, force)
}

/** data:{platform, versionName, ...} → AppVersionInfo；解析失败抛 ClientError。 */
fun decodeVersionInfo(data: JsonElement): AppVersionInfo =
    try {
        AppJson.decodeFromJsonElement(AppVersionInfo.serializer(), data.jsonObject)
    } catch (e: Exception) {
        throw ClientError(0, null, "响应格式无效")
    }
