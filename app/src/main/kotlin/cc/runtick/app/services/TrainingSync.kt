package cc.runtick.app.services

import android.content.Context
import cc.runtick.app.network.APIClient
import kotlinx.serialization.json.*

object TrainingSync {
    private fun prefix(api: APIClient, userId: Long) = "training:${api.baseUrl}:$userId:"
    suspend fun send(context: Context, api: APIClient, userId: Long, path: String, body: JsonObject, id: String) {
        val prefs = context.getSharedPreferences("runtick.training", Context.MODE_PRIVATE)
        val key = prefix(api, userId) + id
        prefs.edit().putString(key, buildJsonObject { put("path", path); put("body", body) }.toString()).commit()
        api.request(path, "POST", body) { Unit }
        prefs.edit().remove(key).apply()
    }
    suspend fun retry(context: Context, api: APIClient, userId: Long) {
        val prefs = context.getSharedPreferences("runtick.training", Context.MODE_PRIVATE)
        for ((key, value) in prefs.all) if (key.startsWith(prefix(api, userId)) && value is String) {
            try {
                val saved = Json.parseToJsonElement(value).jsonObject
                api.request(saved["path"]!!.jsonPrimitive.content, "POST", saved["body"]!!.jsonObject) { Unit }
                prefs.edit().remove(key).apply()
            } catch (_: Exception) { return }
        }
    }
    fun reactionBody(ms: Double?, status: String) = buildJsonObject {
        put("reactionMs", ms?.let { JsonPrimitive(it) } ?: JsonNull); put("reactionStatus", status)
    }
}
