package cc.runtick.app

import cc.runtick.app.core.AppConfiguration
import cc.runtick.app.core.AppJson
import cc.runtick.app.core.PendingEvent
import cc.runtick.app.core.Race
import cc.runtick.app.core.SavedResult
import cc.runtick.app.core.StageDelay
import cc.runtick.app.core.TimingEvent
import cc.runtick.app.core.decodeCurrentRoomCode
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {

    @Test
    fun `网关锁定为固定服务器地址`() {
        assertEquals("https://app.runtick.cc/", AppConfiguration.SERVER_URL)
    }

    // ---------- Race.accepts ----------

    private fun completedRace() = Race(
        id = "race-1", status = "completed", createdAt = 100.0,
        startAt = 1.0, finishAt = 2.0, startUserId = 1, finishUserId = 2,
    )

    @Test
    fun `completed 不能被迟到的 running 回退`() {
        val completed = completedRace()
        val lateRunning = completed.copy(status = "running")
        assertFalse(completed.accepts(lateRunning))
    }

    @Test
    fun `同 race 自身可接受`() {
        val completed = completedRace()
        assertTrue(completed.accepts(completed))
    }

    @Test
    fun `更晚的不同 race 直接接受`() {
        val completed = completedRace()
        val newer = completed.copy(id = "race-2", status = "ready", createdAt = 200.0)
        assertTrue(completed.accepts(newer))
    }

    @Test
    fun `createdAt 更早的 race 被拒绝`() {
        val completed = completedRace()
        val older = completed.copy(id = "race-0", createdAt = 50.0)
        assertFalse(completed.accepts(older))
    }

    @Test
    fun `不允许丢失已保存成绩的回退`() {
        val saved = completedRace().copy(savedResult = SavedResult(1000, true, 12.0))
        assertFalse(saved.accepts(completedRace()))
    }

    // ---------- StageDelay ----------

    @Test
    fun `defaults 全部有效`() {
        val defaults = StageDelay.defaults()
        assertEquals(3, defaults.size)
        assertTrue(defaults.all { it.valid })
    }

    @Test
    fun `非法配置判为无效`() {
        assertFalse(StageDelay(true, 3.0, 2.0).valid)          // 随机且 max < min
        assertFalse(StageDelay(false, Double.NaN, 2.0).valid)  // NaN
        assertFalse(StageDelay(false, 31.0, 2.0).valid)        // min > 10
        assertFalse(StageDelay(false, -0.1, 2.0).valid)        // min < 0
        assertTrue(StageDelay(false, 2.0, 2.0).valid)          // 非随机等值区间合法
        assertTrue(StageDelay(true, 2.0, 2.0).valid)           // 随机等值区间合法
    }

    @Test
    fun `非随机 sample 等于下限`() {
        assertEquals(2.5, StageDelay(false, 2.5, 5.0).sample(), 0.0)
        assertEquals(2.0, StageDelay(true, 2.0, 2.0).sample(), 0.0)
    }

    @Test fun legacyDelaySettingsMigrateToWholeSecondsAndTenSecondLimit() {
        val migrated = StageDelay(true, 1.6, 25.0, enabled = false, reactionEnabled = true).normalized()
        assertEquals(2.0, migrated.min, 0.0); assertEquals(10.0, migrated.max, 0.0)
        assertFalse(migrated.enabled); assertTrue(migrated.reactionEnabled); assertTrue(migrated.valid)
        assertFalse(StageDelay(false, 11.0, 11.0).valid)
        assertFalse(StageDelay(true, 1.5, 5.0).valid)
        assertEquals(StageDelay.defaults(), listOf(StageDelay(false, 5.0, 5.0), StageDelay(false, 5.0, 5.0), StageDelay(true, 5.0, 10.0)))
    }

    // ---------- PendingEvent 序列化 ----------

    @Test
    fun `PendingEvent 序列化往返精度不丢`() {
        val event = PendingEvent(
            code = "1234", raceId = "race-42", userId = 7,
            event = TimingEvent("start", 1760000000000.123, 21.5),
        )
        val raw = AppJson.encodeToString(PendingEvent.serializer(), event)
        val back = AppJson.decodeFromString(PendingEvent.serializer(), raw)
        assertEquals(event, back)
        assertEquals(event.event.at, back.event.at, 0.0)
        assertEquals(event.event.uncertaintyMs, back.event.uncertaintyMs, 0.0)
    }

    @Test
    fun `StageDelay 列表序列化往返`() {
        val stages = StageDelay.defaults()
        val raw = AppJson.encodeToString(ListSerializer(StageDelay.serializer()), stages)
        val back = AppJson.decodeFromString(ListSerializer(StageDelay.serializer()), raw)
        assertEquals(stages, back)
    }

    // ---------- 响应解码 ----------

    @Test
    fun `current-room 解码 room 为 null`() {
        val data = AppJson.parseToJsonElement("""{"room":null}""")
        assertNull(decodeCurrentRoomCode(data))
    }

    @Test
    fun `current-room 解码房间号`() {
        val data = AppJson.parseToJsonElement("""{"room":{"code":"1234","status":"idle"}}""")
        assertEquals("1234", decodeCurrentRoomCode(data))
    }

    @Test
    fun `信封解析取 data 字段`() {
        val envelope = AppJson.parseToJsonElement("""{"ok":true,"data":{"race":null}}""")
        val race = cc.runtick.app.core.decodeRace(envelope)
        assertNull(race)
    }

    @Test
    fun `JSON 元素中 Double 保真`() {
        val element = AppJson.encodeToJsonElement(1760000000000.123)
        assertEquals(1760000000000.123, element.jsonPrimitive.double, 0.0)
        // 序列化字符串可无损解析回同一数值
        val back = AppJson.parseToJsonElement(element.jsonPrimitive.content).jsonPrimitive.double
        assertEquals(1760000000000.123, back, 0.0)
    }
}
