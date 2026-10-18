package cc.runtick.app.services

import android.content.Context
import cc.runtick.app.core.AppJson
import cc.runtick.app.core.PendingEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * 待补交事件（pending）持久化接口。
 * key 约定：pending:{baseUrl}:{userId}:{roomCode}（baseUrl 归一化去尾斜杠）。
 * 接口化以便纯 JVM 单元测试。
 */
interface PendingStore {
    fun save(key: String, event: PendingEvent)
    fun load(key: String): PendingEvent?
    fun delete(key: String)
    fun keysWithPrefix(prefix: String): List<String>
    fun deleteIfUnchanged(key: String, event: PendingEvent) { if (load(key) == event) delete(key) }
}

/** 内存实现（测试用）。 */
class MemoryPendingStore : PendingStore {
    private val map = ConcurrentHashMap<String, PendingEvent>()

    override fun save(key: String, event: PendingEvent) {
        map[key] = event
    }

    override fun load(key: String): PendingEvent? = map[key]

    override fun delete(key: String) {
        map.remove(key)
    }

    override fun deleteIfUnchanged(key: String, event: PendingEvent) { map.remove(key, event) }

    override fun keysWithPrefix(prefix: String): List<String> =
        map.keys.filter { it.startsWith(prefix) }
}

/** SharedPreferences 实现（App 实际使用）。 */
class SharedPreferencesPendingStore(context: Context) : PendingStore {
    private val prefs = context.getSharedPreferences("runtick.pending", Context.MODE_PRIVATE)

    override fun save(key: String, event: PendingEvent) {
        prefs.edit().putString(key, AppJson.encodeToString(PendingEvent.serializer(), event)).apply()
    }

    override fun load(key: String): PendingEvent? =
        prefs.getString(key, null)?.let { raw ->
            runCatching { AppJson.decodeFromString(PendingEvent.serializer(), raw) }.getOrNull()
        }

    override fun delete(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun keysWithPrefix(prefix: String): List<String> =
        prefs.all.keys.filter { it.startsWith(prefix) }
}
