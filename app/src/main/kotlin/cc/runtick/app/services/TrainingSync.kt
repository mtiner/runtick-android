package cc.runtick.app.services

import android.content.Context
import cc.runtick.app.network.APIClient
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException
import cc.runtick.app.core.ClientError

object TrainingSync {
    private val activeRetries = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun prefix(api: APIClient, userId: Long) = "training:${api.baseUrl}:$userId:"
    fun enqueue(context: Context, api: APIClient, userId: Long, path: String, body: JsonObject, id: String) {
        context.getSharedPreferences("runtick.training", Context.MODE_PRIVATE).edit()
            .putString(prefix(api, userId) + id, buildJsonObject { put("path", path); put("body", body) }.toString()).commit()
    }
    suspend fun send(context: Context, api: APIClient, userId: Long, path: String, body: JsonObject, id: String) {
        val prefs = context.getSharedPreferences("runtick.training", Context.MODE_PRIVATE)
        val key = prefix(api, userId) + id
        val saved = buildJsonObject { put("path", path); put("body", body) }.toString()
        prefs.edit().putString(key, saved).commit()
        api.request(path, "POST", body) { Unit }
        if (prefs.getString(key, null) == saved) prefs.edit().remove(key).apply()
    }
    suspend fun retry(context: Context, api: APIClient, userId: Long) {
        val prefs = context.getSharedPreferences("runtick.training", Context.MODE_PRIVATE)
        val namespace = prefix(api, userId)
        if (!activeRetries.add(namespace)) return
        try {
            for ((key, value) in prefs.all) if (key.startsWith(namespace) && value is String) {
                try {
                    val saved = Json.parseToJsonElement(value).jsonObject
                    api.request(saved["path"]!!.jsonPrimitive.content, "POST", saved["body"]!!.jsonObject) { Unit }
                    if (prefs.getString(key, null) == value) prefs.edit().remove(key).apply()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    val status = (e as? ClientError)?.status ?: 0
                    if (status == 0 || status == 401 || status >= 500) return
                }
            }
        } finally { activeRetries.remove(namespace) }
    }
    fun count(context: Context, api: APIClient, userId: Long): Int =
        context.getSharedPreferences("runtick.training", Context.MODE_PRIVATE).all.keys.count { it.startsWith(prefix(api, userId)) }
    fun reactionBody(ms: Double?, status: String) = buildJsonObject {
        put("reactionMs", ms?.let { JsonPrimitive(it) } ?: JsonNull); put("reactionStatus", status)
    }
}
