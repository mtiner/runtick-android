package cc.runtick.app.core

import kotlinx.coroutines.*
import java.net.*
import kotlin.math.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class NtpConfig(
    val servers: List<String> = listOf("ntp.tencent.com", "ntp.aliyun.com"),
    val timeoutMs: Int = 1500, val validSamples: Int = 8, val maxAttempts: Int = 12,
    val intervalMs: Long = 16000, val maxRttMs: Double = 100.0,
    val maxSpreadMs: Double = 10.0,
    val consecutiveFailureLimit: Int = 1,
    val driftPpm: Double = 50.0,
    val maximumAgeMs: Double = 120_000.0,
    val maximumUncertaintyMs: Double = 25.0,
)

/** NTP era is unfolded to the era nearest a trusted approximate UTC pivot (within 68 years). */
object NtpPacket {
    fun timestamp(bytes: ByteArray, at: Int, pivotMs: Double): Double {
        fun word(i: Int): Long = (0..3).fold(0L) { n, j -> (n shl 8) or (bytes[i+j].toLong() and 255) }
        val seconds = word(at).toDouble()
        val pivot = pivotMs / 1000 + 2208988800.0
        val era = round((pivot - seconds) / 4294967296.0)
        return ((seconds + era * 4294967296.0 - 2208988800.0) + word(at+4) / 4294967296.0) * 1000
    }
    fun write(bytes: ByteArray, at: Int, utcMs: Double) {
        val seconds = utcMs / 1000 + 2208988800.0
        val whole = floor(seconds).toLong()
        val fraction = ((seconds - floor(seconds)) * 4294967296.0).toLong()
        for (j in 0..3) { bytes[at+j] = (whole ushr (24-j*8)).toByte(); bytes[at+4+j] = (fraction ushr (24-j*8)).toByte() }
    }
    class Kiss(val code: String, val poll: Int): Exception("NTP Kiss-o'-Death: $code")
    fun parse(bytes: ByteArray, request: ByteArray, t1: Double, t4: Double): ClockSample {
        require(bytes.size >= 48) { "NTP short packet" }
        val header = bytes[0].toInt() and 255
        require((header shr 3 and 7) in 3..4 && header and 7 == 4) { "NTP version/mode" }
        require((24..31).all { bytes[it] == request[it+16] }) { "NTP originate mismatch" }
        val stratum = bytes[1].toInt() and 255
        if (stratum == 0) throw Kiss(String(bytes,12,4,Charsets.US_ASCII), bytes[2].toInt())
        require(header shr 6 != 3 && stratum in 1..15) { "NTP unsynchronized" }
        require((32..39).any { bytes[it].toInt()!=0 } && (40..47).any { bytes[it].toInt()!=0 }) { "NTP zero timestamp" }
        val t2 = timestamp(bytes,32,t1); val t3 = timestamp(bytes,40,t1)
        require(t3 >= t2 && t3-t2 <= 1500 && t4 >= t1) { "NTP invalid time order" }
        val rootDelay = java.nio.ByteBuffer.wrap(bytes,4,4).int / 65536.0 * 1000
        val rootDispersion = (java.nio.ByteBuffer.wrap(bytes,8,4).int.toLong() and 0xffffffffL) / 65536.0 * 1000
        require(rootDelay >= 0 && rootDelay <= 1000 && rootDispersion <= 1000) { "NTP root distance" }
        val sample = ClockSample(t1,t4,t2,t3,rootDelay/2+rootDispersion)
        require(t4-t1-(t3-t2) >= -0.001) { "NTP negative RTT" }
        return sample
    }
}

class NtpClient(private val config: NtpConfig = NtpConfig(),
    private val exchange: (suspend (String) -> ClockSample)? = null,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val blockedUntil = mutableMapOf<String,Double>()
    private val denied = mutableSetOf<String>()
    private var lastRequest = Double.NEGATIVE_INFINITY
    suspend fun synchronize(progress: (Int)->Unit = {}): ClockMapping {
        require(config.validSamples >= 3 && config.maxAttempts >= config.validSamples && config.intervalMs >= 16000 && config.timeoutMs in 1..60000 && config.consecutiveFailureLimit >= 1 && config.servers.isNotEmpty() && config.driftPpm.isFinite() && config.driftPpm >= 0 && config.maximumAgeMs > 0 && config.maximumUncertaintyMs > 0)
        val errors = mutableListOf<String>()
        for (server in config.servers) {
            if (server in denied || MonotonicClock.now() < (blockedUntil[server] ?: 0.0)) continue
            val samples = mutableListOf<ClockSample>()
            var failures = 0
            for (attempt in 0 until config.maxAttempts) {
                currentCoroutineContext().ensureActive()
                val wait = config.intervalMs - (MonotonicClock.now()-lastRequest)
                if (wait > 0) pause(ceil(wait).toLong())
                lastRequest = MonotonicClock.now()
                try {
                    val sample = exchange?.invoke(server) ?: request(server)
                    failures = 0
                    val rtt = sample.receivedAt-sample.sentAt-(sample.serverSentAt-sample.serverReceivedAt)
                    android.util.Log.d("RunTickNTP", "$server rtt=$rtt offset=${((sample.serverReceivedAt-sample.sentAt)+(sample.serverSentAt-sample.receivedAt))/2}")
                    if (rtt <= config.maxRttMs) samples.add(sample)
                    progress(samples.size)
                    if (samples.size >= config.validSamples) {
                        val result = NtpQuality.select(samples,config.driftPpm)
                        val offsets = samples.map { ((it.serverReceivedAt-it.sentAt)+(it.serverSentAt-it.receivedAt))/2 }
                        if (offsets.max()-offsets.min() > config.maxSpreadMs) { errors.add("$server: unstable offsets"); break }
                        val local = MonotonicClock.now()
                        return result.copy(server=server, validSamples=samples.size, referenceMono=MonotonicClock.nanosAt(local), referenceUTC=local+result.offsetMs, driftPpm=config.driftPpm, maximumAgeMs=config.maximumAgeMs, maximumUncertaintyMs=config.maximumUncertaintyMs).also { require(it.fresh) { "NTP uncertainty budget exceeded" } }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: NtpPacket.Kiss) {
                    if (e.code == "DENY" || e.code == "RSTR") denied.add(server)
                    blockedUntil[server] = MonotonicClock.now()+max(60000.0,2.0.pow(e.poll.coerceIn(4,17))*1000)
                    errors.add("$server: ${e.message}"); break
                } catch (e: Exception) { android.util.Log.d("RunTickNTP", "$server error=${e.message}"); errors.add("$server: ${e.message}"); failures++; if (failures >= config.consecutiveFailureLimit) break }
            }
            errors.add("$server: accepted ${samples.size}/${config.validSamples} samples; quality not accepted")
        }
        error("NTP unavailable: ${errors.joinToString()}")
    }
    private suspend fun request(server: String): ClockSample = withContext(Dispatchers.IO) {
        val task = currentCoroutineContext()
        suspendCancellableCoroutine { continuation ->
            val active = java.util.concurrent.atomic.AtomicReference<DatagramSocket?>()
            continuation.invokeOnCancellation { active.getAndSet(null)?.close() }
            try {
                val deadline = MonotonicClock.now() + config.timeoutMs
                val addresses = InetAddress.getAllByName(server)
                var failure: Exception? = null
                for (address in addresses) {
                    task.ensureActive()
                    try {
                        DatagramSocket().use { socket ->
                            active.set(socket); task.ensureActive()
                            val remaining = deadline - MonotonicClock.now()
                            if (remaining <= 0) throw SocketTimeoutException("NTP request deadline exceeded")
                            socket.soTimeout = ceil(remaining).toInt().coerceAtLeast(1)
                            socket.connect(address,123)
                            val bytes = ByteArray(48); bytes[0]=0x23
                            // Frozen UTC bridge; never mix raw elapsed time with server UTC.
                            val t1 = MonotonicClock.now(); NtpPacket.write(bytes,40,t1)
                            socket.send(DatagramPacket(bytes,bytes.size))
                            val response = DatagramPacket(ByteArray(512),512)
                            socket.receive(response); val t4=MonotonicClock.now()
                            task.ensureActive()
                            continuation.resume(NtpPacket.parse(response.data.copyOf(response.length),bytes,t1,t4))
                            return@suspendCancellableCoroutine
                        }
                    } catch (e: NtpPacket.Kiss) { throw e }
                    catch (e: CancellationException) { throw e }
                    catch (e: IllegalArgumentException) { throw e }
                    catch (e: Exception) { failure=e }
                    finally { active.set(null) }
                }
                throw failure ?: IllegalStateException("NTP DNS empty")
            } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
        }
    }
}

/** Conservative engineering model, not measured absolute accuracy or a guaranteed bound.
 * Assumes path asymmetry no greater than observed network delay and honest server metadata.
 */
object NtpQuality {
    fun select(samples: List<ClockSample>, driftPpm: Double = 50.0): ClockMapping {
        val sorted = samples.sortedBy { it.receivedAt-it.sentAt-(it.serverSentAt-it.serverReceivedAt) }
        val selected = sorted.take(max(3, samples.size/2))
        val offsets = selected.map { ((it.serverReceivedAt-it.sentAt)+(it.serverSentAt-it.receivedAt))/2 }.sorted()
        val offset = offsets[offsets.size/2]
        val rtt = selected.map { it.receivedAt-it.sentAt-(it.serverSentAt-it.serverReceivedAt) }.average()
        val spread = offsets.maxOf { abs(it-offset) }
        val span = max(0.0,samples.maxOf { it.receivedAt } - samples.minOf { it.sentAt })
        val samplingDrift = span*driftPpm/1_000_000.0
        val estimate = samplingDrift + rtt/2 + selected.maxOf { it.rootDistanceMs } + spread + 2.0
        require(estimate.isFinite() && estimate <= 25) { "NTP quality rejected" }
        return ClockMapping(offset,estimate,rtt,MonotonicClock.now())
    }
}

object NativeTimeSync {
    private var client = NtpClient()
    private val mutex = kotlinx.coroutines.sync.Mutex()
    @Volatile private var needsRefresh = false
    @Volatile var latest: ClockMapping? = null
        private set
    suspend fun synchronize(progress: (Int)->Unit = {}): ClockMapping {
        mutex.lock()
        try {
            val cached = latest
            if (cached?.fresh == true && !needsRefresh) return cached
            return client.synchronize(progress).also { latest=it; needsRefresh=false }
        } finally { mutex.unlock() }
    }
    suspend fun configure(config: NtpConfig) { mutex.lock(); try { client=NtpClient(config); needsRefresh=true } finally { mutex.unlock() } }
    fun invalidate() { needsRefresh=true }
}
