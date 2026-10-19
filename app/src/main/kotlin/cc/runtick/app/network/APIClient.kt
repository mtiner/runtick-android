package cc.runtick.app.network

import android.content.Context
import cc.runtick.app.core.AppConfiguration
import cc.runtick.app.core.AppJson
import cc.runtick.app.core.ClientError
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * API 客户端：统一信封解析（{ok, data, error}）、Cookie 会话。
 * 请求不携带 Origin 头（服务端同源校验只拦跨源 Origin）。
 */
class APIClient(val baseUrl: String = AppConfiguration.SERVER_URL, context: Context? = null) {

    private val jar = PersistentCookieJar(context)
    private val sessionPrefs = context?.getSharedPreferences("runtick.session", Context.MODE_PRIVATE)
    val savedUser: cc.runtick.app.core.User? get() = sessionPrefs?.getString("user", null)?.let {
        runCatching { AppJson.decodeFromString(cc.runtick.app.core.User.serializer(), it) }.getOrNull()
    }
    fun remember(user: cc.runtick.app.core.User) {
        sessionPrefs?.edit()?.putString("user", AppJson.encodeToString(cc.runtick.app.core.User.serializer(), user))?.apply()
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(jar)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /** 收到 401 时回调（一般用于清理本地会话）。 */
    var unauthorized: (() -> Unit)? = null

    private fun url(path: String): HttpUrl =
        baseUrl.toHttpUrl().resolve(path.trimStart('/'))
            ?: throw ClientError(0, null, "无效的服务器地址")

    /**
     * 发起请求并解出 data 字段。
     * HTTP 非 2xx 或 ok=false 都抛 ClientError。
     */
    suspend fun <T> request(
        path: String,
        method: String = "GET",
        body: JsonElement? = null,
        decode: (JsonElement) -> T,
    ): T {
        val requestBody = when {
            body != null -> AppJson.encodeToString(JsonElement.serializer(), body)
                .toRequestBody("application/json".toMediaType())
            // OkHttp 要求 POST/PUT 等必须携带 body，无内容时发空体
            method != "GET" && method != "HEAD" -> ByteArray(0).toRequestBody()
            else -> null
        }
        val call = client.newCall(Request.Builder().url(url(path)).method(method, requestBody).build())
        val response = try {
            suspendCancellableCoroutine<Pair<Int, String>> { cont ->
                call.enqueue(object : Callback {
                    override fun onResponse(call: Call, response: Response) {
                        if (!cont.isActive) { response.close(); return }
                        try {
                            // OkHttp invokes this callback on its worker thread. Consume the
                            // body here; resuming the caller first would read from the UI thread.
                            val result = response.use { Pair(it.code, it.body?.string().orEmpty()) }
                            if (cont.isActive) cont.resume(result)
                        } catch (e: IOException) {
                            if (cont.isActive) cont.resumeWithException(e)
                        }
                    }

                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                })
                cont.invokeOnCancellation { call.cancel() }
            }
        } catch (e: IOException) {
            throw networkError(e)
        }
        val (status, text) = response
        if (status == 401) unauthorized?.invoke()
        run {
            val envelope = runCatching { AppJson.parseToJsonElement(text).jsonObject }.getOrNull()
            val ok = (envelope?.get("ok") as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull
            val data = envelope?.get("data")?.takeIf { it !is JsonNull }
            val errorPayload = envelope?.get("error") as? kotlinx.serialization.json.JsonObject
            val code = (errorPayload?.get("code") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            val message = (errorPayload?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            if (status !in 200..299 || ok != true || data == null) {
                throw ClientError(status, code ?: "UNKNOWN", when { status == 401 -> "登录状态已过期，请重新登录。"; status >= 500 -> "服务暂时不可用，请稍后重试。"; else -> message ?: "暂时无法读取服务器响应，请稍后重试。" })
            }
            try { return decode(data) }
            catch (e: kotlinx.serialization.SerializationException) { throw ClientError(status, "INVALID_RESPONSE", "暂时无法读取服务器响应，请稍后重试。") }
        }
    }

    /** 清空会话 Cookie（登出或 401 时调用）。 */
    fun clearCookies() {
        jar.clear()
        sessionPrefs?.edit()?.clear()?.apply()
    }

    /** 网络层异常 → 中文提示。 */
    private fun networkError(e: IOException): ClientError = ClientError(0, null, when (e) {
        is SocketTimeoutException -> "连接服务器超时，请检查网络后重试"
        is ConnectException, is UnknownHostException, is NoRouteToHostException, is PortUnreachableException ->
            "网络连接已断开，请检查网络设置"
        else -> "暂时无法连接服务器，请稍后重试"
    })
}

/**
 * 持久化 CookieJar：基于 SharedPreferences 保存会话 Cookie，
 * 支持 App 重启后 restore() 恢复会话。
 */
private class PersistentCookieJar(context: Context?) : CookieJar {
    private val prefs = context?.getSharedPreferences("runtick.cookies", Context.MODE_PRIVATE)
    private val cache = LinkedHashMap<String, String>()

    init {
        synchronized(this) {
            prefs?.all?.forEach { (key, value) -> if (value is String) cache[key] = value }
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(this) {
            for (cookie in cookies) {
                cache["${cookie.name}|${cookie.domain}|${cookie.path}"] = cookie.toString()
            }
            persist()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(this) {
        cache.values.mapNotNull { raw ->
            runCatching { Cookie.parse(url, raw) }.getOrNull()
        }.filter { it.matches(url) }
    }

    fun clear() {
        synchronized(this) {
            cache.clear()
            prefs?.edit()?.clear()?.apply()
        }
    }

    private fun persist() {
        val editor = prefs?.edit() ?: return
        editor.clear()
        cache.forEach { (key, value) -> editor.putString(key, value) }
        editor.apply()
    }
}
