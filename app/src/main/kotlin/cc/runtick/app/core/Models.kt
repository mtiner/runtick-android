package cc.runtick.app.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.random.Random

/** 全局 JSON 配置：忽略未知字段，容错解析。 */
val AppJson: Json = Json { ignoreUnknownKeys = true; isLenient = true }

@Serializable
data class User(val id: Long, val loginId: String)

@Serializable
data class SavedResult(val distance: Int, val isPb: Boolean, val pbImprovementMs: Double? = null)

@Serializable
data class Race(
    val id: String,
    val status: String,
    val createdAt: Double,
    val startAt: Double? = null,
    val finishAt: Double? = null,
    val elapsedMs: Double? = null,
    val uncertaintyMs: Double? = null,
    val startUserId: Long,
    val finishUserId: Long,
    val starterSettings: List<StageDelay>? = null,
    val savedResult: SavedResult? = null,
    val reactionMs: Double? = null, val reactionStatus: String? = null,
) {
    /** ready/running 视为进行中。 */
    val active: Boolean get() = status == "ready" || status == "running"

    /**
     * 乱序保护：newer.createdAt 不早于当前状态才可能接受。
     * 同一 race 不允许 completed→active、丢失 savedResult/startAt/finishAt 的回退；
     * id 不同的更新 race 直接接受。
     */
    fun accepts(newer: Race): Boolean {
        if (newer.createdAt < createdAt) return false
        if (newer.id != id) return true
        return !(!active && newer.active) &&
            !(savedResult != null && newer.savedResult == null) &&
            !(startAt != null && newer.startAt == null) &&
            !(finishAt != null && newer.finishAt == null)
    }
}

@Serializable
data class HistoryRecord(
    val raceId: String,
    val athleteId: Long,
    val recorderId: Long,
    val distance: Int,
    val elapsedMs: Double,
    val performedAt: Double,
    val savedAt: Double,
    val isPb: Boolean,
    val pbImprovementMs: Double? = null,
    val reactionMs: Double? = null, val reactionStatus: String? = null,
)

/** 三段发令音的等待时间（秒）。 */
@Serializable
data class StageDelay(var random: Boolean, var min: Double, var max: Double, var enabled: Boolean = true, var reactionEnabled: Boolean = false) {
    /** 上下限须有限且落在 0–10 秒，随机时上限不得小于下限。 */
    val valid: Boolean
        get() = min.isFinite() && max.isFinite() && min == kotlin.math.round(min) && max == kotlin.math.round(max) &&
            min in 0.0..10.0 && max in 0.0..10.0 && (!random || max >= min)

    fun normalized(): StageDelay {
        val lower = if (min.isFinite()) kotlin.math.round(min).coerceIn(0.0, 10.0) else 5.0
        val upper = if (max.isFinite()) kotlin.math.round(max).coerceIn(lower, 10.0) else lower
        return copy(min = lower, max = if (random) upper else lower)
    }

    /** 非随机取下限；随机的等值区间（max<=min）同样取下限。 */
    fun sample(): Double = when {
        !random -> min
        max <= min -> min
        else -> Random.nextDouble(min, max)
    }

    companion object {
        fun defaults(): List<StageDelay> = listOf(
            StageDelay(false, 5.0, 5.0),
            StageDelay(false, 5.0, 5.0),
            StageDelay(true, 5.0, 10.0),
        )
    }
}

/**
 * Frozen initial UTC bridge over elapsedRealtimeNanos, including device suspend.
 * Only calibrated ClockMapping values may be used for cross-device timing.
 */
object MonotonicClock {
    private val anchorNanos = android.os.SystemClock.elapsedRealtimeNanos()
    private val anchorUTC = System.currentTimeMillis().toDouble()
    fun rawNanos(): Long = android.os.SystemClock.elapsedRealtimeNanos()
    fun nanosAt(localMs: Double): Long = anchorNanos + ((localMs-anchorUTC)*1_000_000).toLong()
    fun now(): Double = anchorUTC + (rawNanos() - anchorNanos) / 1_000_000.0
    // AudioTimestamp uses CLOCK_MONOTONIC (excludes suspend); bridge at capture.
    fun fromNanos(nanos: Long): Double = now() + (nanos - System.nanoTime()) / 1_000_000.0
}

/** 单次校时样本（客户端本地时间 + 服务器回执时间）。 */
data class ClockSample(
    val sentAt: Double,
    val receivedAt: Double,
    val serverReceivedAt: Double,
    val serverSentAt: Double,
    val rootDistanceMs: Double = 0.0,
)

/** 校时结果：offset 把本地单调时间换算到服务器时间域。 */
data class ClockMapping(
    val offsetMs: Double,
    val uncertaintyMs: Double,
    val avgRttMs: Double,
    val sampledAtMs: Double,
    val server: String = "HTTP",
    val validSamples: Int = 0,
    val driftPpm: Double = 50.0,
    val maximumAgeMs: Double = 120_000.0,
    val maximumUncertaintyMs: Double = 25.0,
    val frequencyScale: Double = 1.0,
    val referenceMono: Long? = null,
    val referenceUTC: Double? = null,
) {
    /** 校时结果 120 秒内视为新鲜。 */
    fun utcAt(localMs: Double): Double = if (referenceMono != null && referenceUTC != null) referenceUTC + (MonotonicClock.nanosAt(localMs)-referenceMono)/1_000_000.0*frequencyScale else localMs+offsetMs
    val ageMs: Double get() = (MonotonicClock.now() - sampledAtMs).coerceAtLeast(0.0)
    val estimatedUncertaintyMs: Double get() = uncertaintyMs + ageMs * driftPpm / 1_000_000.0
    val fresh: Boolean get() = ageMs <= maximumAgeMs && estimatedUncertaintyMs <= maximumUncertaintyMs

    companion object {
        /**
         * NTP 风格求平均：
         * RTT = received - sent - (serverSent - serverReceived)
         * offset = ((serverReceived - sent) + (serverSent - received)) / 2
         * 不确定度 = 平均RTT/2 + 最大偏差 + 1；超 250ms 或出现负 RTT 视为失败。
         */
        fun average(samples: List<ClockSample>): ClockMapping {
            if (samples.isEmpty()) throw ClientError(0, null, "网络延迟或波动过高，请重试校时")
            val rtts = samples.map { it.receivedAt - it.sentAt - (it.serverSentAt - it.serverReceivedAt) }
            val offsets = samples.map { ((it.serverReceivedAt - it.sentAt) + (it.serverSentAt - it.receivedAt)) / 2.0 }
            val avgRtt = rtts.sum() / samples.size
            val avgOffset = offsets.sum() / samples.size
            val uncertainty = avgRtt / 2 + offsets.maxOf { abs(it - avgOffset) } + 1.0
            if (!avgOffset.isFinite() || !uncertainty.isFinite() || uncertainty > 250.0 || rtts.any { it < 0 }) {
                throw ClientError(0, null, "网络延迟或波动过高，请重试校时")
            }
            return ClockMapping(avgOffset, uncertainty, avgRtt, MonotonicClock.now())
        }
    }
}

/** 提交给服务器的计时事件（kind: "start" | "finish"，at 为服务器时间域毫秒）。 */
@Serializable
data class TimingEvent(val kind: String, val at: Double, val uncertaintyMs: Double,
    val monoNanos: String? = null, val deviceId: String? = null, val sessionId: String? = null,
    val syncServer: String? = null, val syncRttMs: Double? = null, val syncAgeMs: Double? = null,
    val syncOffsetMs: Double? = null, val syncSource: String? = null)

/** 待补交事件（冲线/枪声先落盘，网络恢复后重试）。 */
@Serializable
data class PendingEvent(val code: String, val raceId: String, val userId: Long, val event: TimingEvent)

// ---------- 响应解码辅助 ----------

@Serializable
data class Room(val code: String, val status: String? = null, val createdAt: String? = null)

/** 房间成员状态（房间详情与 WebSocket state 共用）。 */
@Serializable
data class MemberState(
    val loginId: String,
    val deviceRole: String? = null,
    val online: Boolean = false,
    val role: String? = null,
)

@Serializable
data class RoomDetailData(val room: Room, val ownerLoginId: String, val members: List<MemberState> = emptyList())

/** WebSocket state 消息。 */
@Serializable
data class HubState(val type: String, val members: List<MemberState>? = null, val ready: Boolean? = null)

/** /api/clock 响应。 */
@Serializable
data class ClockResponse(val clientSentAt: Double, val receivedAt: Double, val sentAt: Double, val processingMs: Double)

/** 统一把解析失败包装为 ClientError。 */
private inline fun <T> decodeOrThrow(block: () -> T): T =
    try {
        block()
    } catch (e: ClientError) {
        throw e
    } catch (e: Exception) {
        throw ClientError(0, null, "响应格式无效")
    }

private fun JsonObject.require(key: String): JsonElement =
    this[key]?.takeIf { it !is JsonNull } ?: throw ClientError(0, null, "响应格式无效")

/** data:{user:{...}} */
fun decodeUser(data: JsonElement): User = decodeOrThrow {
    AppJson.decodeFromJsonElement(User.serializer(), data.jsonObject.require("user"))
}

/** data:{room:{...}}（房间不可缺失）。 */
fun decodeRoom(data: JsonElement): Room = decodeOrThrow {
    AppJson.decodeFromJsonElement(Room.serializer(), data.jsonObject.require("room"))
}

/** data:{room:...|null} → 房间号或 null。 */
fun decodeCurrentRoomCode(data: JsonElement): String? = decodeOrThrow {
    val room = data.jsonObject["room"]
    if (room == null || room is JsonNull) null
    else AppJson.decodeFromJsonElement(Room.serializer(), room).code
}

/** data:{room, ownerLoginId, members:[...]} */
fun decodeRoomDetail(data: JsonElement): RoomDetailData = decodeOrThrow {
    AppJson.decodeFromJsonElement(RoomDetailData.serializer(), data)
}

/** data:{deviceRole} */
fun decodeDeviceRole(data: JsonElement): String = decodeOrThrow {
    data.jsonObject.require("deviceRole").jsonPrimitive.contentOrNull
        ?: throw ClientError(0, null, "响应格式无效")
}

/** data:{race:...|null} */
fun decodeRace(data: JsonElement): Race? = decodeOrThrow {
    val race = data.jsonObject["race"]
    if (race == null || race is JsonNull) null
    else AppJson.decodeFromJsonElement(Race.serializer(), race)
}

/** data:{result, created} → result。 */
fun decodeHistoryRecord(data: JsonElement): HistoryRecord = decodeOrThrow {
    AppJson.decodeFromJsonElement(HistoryRecord.serializer(), data.jsonObject.require("result"))
}

/** data:{clientSentAt, receivedAt, sentAt, processingMs} */
fun decodeClock(data: JsonElement): ClockResponse = decodeOrThrow {
    AppJson.decodeFromJsonElement(ClockResponse.serializer(), data)
}
