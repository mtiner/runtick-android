package cc.runtick.app.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

data class SuningTimeConfig(
    val endpoint: String = "https://f.m.suning.com/api/ct.do",
    val validSamples: Int = 8, val maxAttempts: Int = 12,
    val timeoutMs: Long = 1500, val intervalMs: Long = 0,
    val maxRttMs: Double = 300.0, val maxSpreadMs: Double = 50.0,
    val maximumUncertaintyMs: Double = 250.0, val maximumAgeMs: Double = 120000.0,
)
data class HTTPTimeSample(val sent: Double, val received: Double, val serverUTC: Double) {
    val rtt get() = received-sent
    val offset get() = serverUTC-(sent+(received-sent)/2)
}
class HTTPTimeRateLimit(val delayMs: Double): Exception("HTTP 时间源限流或暂不可用，约 ${kotlin.math.ceil(delayMs/1000).toLong()} 秒后可重试")
object SuningTimeProtocol {
    fun retryDelayMs(status: Int,retryAfter: String?,nowMs: Long = System.currentTimeMillis()): Double? {
        if(status!=429 && status!=503) return null
        retryAfter?.toDoubleOrNull()?.takeIf { it.isFinite() && it>=0 }?.let { return (it.coerceAtMost(31536000.0)*1000).coerceAtLeast(1000.0) }
        if(retryAfter!=null) {
            val formatter=java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz",java.util.Locale.US)
            formatter.timeZone=java.util.TimeZone.getTimeZone("GMT")
            runCatching { formatter.parse(retryAfter) }.getOrNull()?.let { return (it.time-nowMs).toDouble().coerceAtLeast(1000.0) }
        }
        return 60000.0
    }
    @Serializable private data class Response(val api: String,val code: String,val currentTime: Long)
    fun timestamp(data: String,status: Int,age: String? = null): Double {
        require(status==200 && data.length<=16384 && (age==null || age.toDoubleOrNull()==0.0)) { "苏宁时间响应无效或被缓存" }
        val response = AppJson.decodeFromString<Response>(data)
        require(response.api=="time" && response.code=="1" && response.currentTime in 946684800000L..4102444800000L) { "苏宁时间响应无效" }
        return response.currentTime.toDouble()
    }
    fun select(samples: List<HTTPTimeSample>,config: SuningTimeConfig): ClockMapping {
        require(samples.size>=config.validSamples && samples.all { it.rtt.isFinite() && it.rtt>=0 && it.rtt<=config.maxRttMs && it.offset.isFinite() }) { "HTTP 有效样本不足" }
        val offsets = samples.map { it.offset }
        require(offsets.max()-offsets.min()<=config.maxSpreadMs) { "HTTP 时间偏移不稳定，请重试" }
        val selected = samples.sortedBy { it.rtt }.take(maxOf(3,config.validSamples/2))
        val center = selected.map { it.offset }.sorted()[selected.size/2]
        val spread = selected.maxOf { abs(it.offset-center) }
        // Heuristic budget, not measured accuracy: server accuracy, timestamp semantics,
        // processing and path asymmetry remain unverified. RTT/2 is only one component.
        val span = (samples.maxOf { it.received }-samples.minOf { it.sent }).coerceAtLeast(0.0)
        val estimate = selected.maxOf { it.rtt }/2+spread+25+span*50/1000000
        require(estimate<=config.maximumUncertaintyMs) { "HTTP 校时超出估计不确定度预算" }
        val local = MonotonicClock.now()
        return ClockMapping(center,estimate,selected.map { it.rtt }.average(),local,
            server="f.m.suning.com",validSamples=samples.size,
            maximumAgeMs=config.maximumAgeMs,maximumUncertaintyMs=config.maximumUncertaintyMs,
            referenceMono=MonotonicClock.nanosAt(local),referenceUTC=local+center)
    }
}
class SuningTimeClient(
    val config: SuningTimeConfig = SuningTimeConfig(),
    private val exchange: (suspend ()->HTTPTimeSample)? = null,
    private val pause: suspend (Long)->Unit = { delay(it) },
) {
    @Volatile var latestRTT: Double? = null
        private set
    @Volatile var averageRTT: Double? = null
        private set
    @Volatile private var blockedUntil = Double.NEGATIVE_INFINITY
    // Isolated from API cookies and response caches. Redirects are rejected.
    private val client = OkHttpClient.Builder().callTimeout(config.timeoutMs,TimeUnit.MILLISECONDS)
        .followRedirects(false).followSslRedirects(false).cache(null).build()
    suspend fun synchronize(report: (Int,String)->Unit = { _,_-> }): ClockMapping {
        if(MonotonicClock.now()<blockedUntil) throw HTTPTimeRateLimit(blockedUntil-MonotonicClock.now())
        require(config.validSamples>=3 && config.maxAttempts>=config.validSamples && config.intervalMs>=0 && config.timeoutMs>0 && config.maxRttMs>0 && config.maxSpreadMs>=0 && config.maximumAgeMs>0 && config.maximumUncertaintyMs>0 && config.endpoint.startsWith("https://")) { "HTTP 校时配置无效" }
        latestRTT=null; averageRTT=null
        val samples = mutableListOf<HTTPTimeSample>(); var lastTimestamp: Double? = null
        var lastError = ""
        repeat(config.maxAttempts) { attempt ->
            currentCoroutineContext().ensureActive()
            if(attempt>0 && config.intervalMs>0) pause(config.intervalMs)
            report(samples.size.coerceAtMost(config.validSamples),"苏宁 HTTP · 第 ${attempt+1} 次请求")
            try {
                val sample = exchange?.invoke() ?: request()
                latestRTT=sample.rtt
                require(sample.rtt>=0 && sample.rtt<=config.maxRttMs && sample.offset.isFinite() && (lastTimestamp==null || sample.serverUTC>lastTimestamp!!)) { "延迟超标或服务器时间未推进" }
                lastTimestamp=sample.serverUTC; samples.add(sample)
                averageRTT=samples.map { it.rtt }.average()
                report(samples.size.coerceAtMost(config.validSamples),"苏宁 HTTP · RTT %.1f ms · Offset %.1f ms".format(sample.rtt,sample.offset))
                if(samples.size>=config.validSamples) {
                    val mapping = SuningTimeProtocol.select(samples.takeLast(config.validSamples),config)
                    report(config.validSamples,"苏宁 HTTP · 校时完成，实际精度未经验证")
                    return mapping
                }
            } catch(e: CancellationException) { throw e }
            catch(e: HTTPTimeRateLimit) {
                blockedUntil=MonotonicClock.now()+e.delayMs
                report(samples.size.coerceAtMost(config.validSamples),e.message ?: "HTTP 时间源限流")
                throw e
            }
            catch(e: Exception) { lastError=e.message ?: "请求失败"; report(samples.size.coerceAtMost(config.validSamples),"苏宁 HTTP · $lastError") }
        }
        error("苏宁 HTTP 有效样本或质量不足（${samples.size.coerceAtMost(config.validSamples)}/${config.validSamples}）：$lastError")
    }
    private suspend fun request(): HTTPTimeSample = suspendCancellableCoroutine { continuation ->
        val url = config.endpoint.toHttpUrl().newBuilder().addQueryParameter("_runtick",UUID.randomUUID().toString()).build()
        val call = client.newCall(Request.Builder().url(url).header("Cache-Control","no-cache, no-store").header("Pragma","no-cache").build())
        continuation.invokeOnCancellation { call.cancel() }
        val sent = MonotonicClock.now()
        call.enqueue(object: Callback {
            override fun onFailure(call: Call,e: IOException) { if(continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call,response: Response) {
                val result = runCatching {
                    response.use {
                        SuningTimeProtocol.retryDelayMs(it.code,it.header("Retry-After"))?.let { delay -> throw HTTPTimeRateLimit(delay) }
                        val source = it.body?.source() ?: error("HTTP 时间响应为空")
                        source.request(16385)
                        require(source.buffer.size<=16384) { "HTTP 时间响应过长" }
                        val bytes=source.readUtf8()
                        val received=MonotonicClock.now()
                        val timestamp=SuningTimeProtocol.timestamp(bytes,it.code,it.header("Age"))
                        HTTPTimeSample(sent,received,timestamp)
                    }
                }
                if(continuation.isActive) result.fold({continuation.resume(it)},{continuation.resumeWithException(it)})
            }
        })
    }
}

object AppTimeSync {
    enum class Source { SUNING_HTTP, NTP }
    var source = Source.SUNING_HTTP
    private val client = SuningTimeClient()
    private val mutex = Mutex()
    private val state = MutableStateFlow(0 to "准备 HTTP 校时")
    @Volatile private var stale = false
    private val generation = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var httpLatest: ClockMapping? = null
    val latest get() = if(source==Source.NTP) NativeTimeSync.latest else httpLatest
    val target get() = if(source==Source.NTP) 8 else client.config.validSamples
    val latestRTT get()=client.latestRTT
    val averageRTT get()=client.averageRTT
    val detail get() = state.value.second
    fun invalidate() { stale=true; generation.incrementAndGet(); NativeTimeSync.invalidate() }
    suspend fun synchronize(progress: (Int)->Unit = {}): ClockMapping = coroutineScope {
        if(source==Source.NTP) return@coroutineScope NativeTimeSync.synchronize(progress)
        val observer = launch(start=CoroutineStart.UNDISPATCHED) { state.collect { progress(it.first) } }
        try {
            mutex.withLock {
                httpLatest?.takeIf { it.fresh && !stale } ?: try {
                    val startedGeneration = generation.get()
                    client.synchronize { count,message -> state.value=count to message }.also {
                        check(startedGeneration==generation.get()) { "网络环境已变化，请重新校时" }
                        httpLatest=it; stale=false
                    }
                } catch(e: Exception) {
                    state.value=state.value.first to "苏宁 HTTP 校时失败：${e.message}"
                    throw e
                }
            }.also { progress(it.validSamples) }
        } finally { observer.cancel() }
    }
}
