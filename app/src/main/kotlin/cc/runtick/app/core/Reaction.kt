package cc.runtick.app.core

import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable data class ReactionResult(val reactionMs: Double? = null, val reactionStatus: String)
@Serializable data class SoloRecord(val id: String, val createdAt: Double, val reactionMs: Double? = null, val reactionStatus: String)
@Serializable data class SoloRecords(val records: List<SoloRecord> = emptyList(), val hasMore: Boolean = false)
fun reactionText(ms: Double?, status: String?): String = when {
    status == "false_start" && ms != null -> "抢跑 · ${"%.1f".format(ms)} ms（＜100 ms）"
    status == "detected" && ms != null -> "起跑反应 ${"%.1f".format(ms)} ms"
    else -> mapOf("disabled" to "未启用反应时检测", "timeout" to "未检测到移动", "unavailable" to "传感器不可用或未授权", "unstable" to "放置不稳定", "cancelled" to "检测已中断")[status] ?: "未记录反应时"
}
class MotionGate {
    var ready = false; private set
    var movedAt: Double? = null; private set
    private var armedAt: Double? = null
    fun arm(at: Double) { ready = true; movedAt = null; candidate = null; armedAt = at }
    private var candidate: Double? = null
    fun sample(at: Double, acceleration: Double, rotation: Double) {
        if (!at.isFinite() || !acceleration.isFinite() || !rotation.isFinite()) return
        if (armedAt != null && at < armedAt!!) return
        if (!ready) return
        if (movedAt != null) return
        if ((acceleration > 0.45 && rotation > 0.2) || acceleration > 1.5) {
            if (candidate == null) candidate = at
            if (at - candidate!! >= 25) movedAt = candidate
        } else candidate = null
    }
}
