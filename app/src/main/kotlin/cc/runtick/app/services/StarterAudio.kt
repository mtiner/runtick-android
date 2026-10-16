package cc.runtick.app.services

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import cc.runtick.app.core.ClientError
import cc.runtick.app.core.MonotonicClock
import cc.runtick.app.core.StageDelay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * 发令音频：三段原声（各就位 → 预备 → 枪声）共用一条 AudioTrack 时间线，
 * 全部样本连续输出，因此任意帧的输出时刻可由 AudioTimestamp 精确反推。
 * 枪声时间戳描述的是「扬声器实际出声的时刻」，误差计入额外不确定度 20ms。
 */
class StarterAudio(private val context: Context) {

    private val generation = AtomicLong()
    private var scope: CoroutineScope? = null
    private var track: AudioTrack? = null

    /** 停止播放并释放资源；使在途的 fire() 以取消结束。 */
    fun stop() {
        generation.incrementAndGet()
        scope?.cancel()
        scope = null
        track?.let { t ->
            runCatching { t.pause() }
            t.release()
        }
        track = null
    }

    /**
     * 调度三段音频并依次回调提示语，返回 (枪声时刻ms, 额外不确定度ms=20)。
     * clicked 为点击「发令」的本地单调时刻。
     */
    suspend fun fire(clicked: Double, delays: List<StageDelay>, onCue: (String) -> Unit): Pair<Double, Double> {
        stop()
        if (delays.size != 3 || delays.any { !it.valid }) {
            throw ClientError(0, null, "等待时间须为 0–30 秒，随机上限不得小于下限")
        }
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (manager.getStreamVolume(AudioManager.STREAM_MUSIC) < maxVolume * 0.999) {
            throw ClientError(0, null, "请先将媒体音量调至最大，发令音必须清晰可靠")
        }
        val clips = listOf("on_your_marks", "set", "gun").map { loadWav(it) }
        val sampleRate = clips[0].sampleRate
        if (clips.any { it.sampleRate != sampleRate || it.channels != clips[0].channels }) {
            throw ClientError(0, null, "发令音频格式不一致")
        }
        val channelConfig = if (clips[0].channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val bytesPerFrame = clips[0].channels * 2
        val framesPerMs = sampleRate / 1000.0

        val sampled = delays.map { it.sample() }
        val anchor = MonotonicClock.now()
        var at = max(anchor + 100.0, clicked + sampled[0] * 1000.0)
        val times = mutableListOf(at)
        for (i in 1..2) {
            at += clips[i - 1].durationMs + sampled[i] * 1000.0
            times.add(at)
        }

        val token = generation.incrementAndGet()
        val audioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = audioScope
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minBuffer * 8, 65536))
            .build()
        track = audioTrack
        audioTrack.play()

        // 后台协程按时间线写入：静音垫到各就位 → 片段0 → 静音 → 片段1 → 静音 → 枪声
        audioScope.launch {
            try {
                var writtenFrames = 0L
                for (i in 0..2) {
                    val target = ((times[i] - anchor) * framesPerMs).roundToLong().coerceAtLeast(0)
                    val gap = target - writtenFrames
                    if (gap > 0) {
                        writeSilence(audioTrack, gap, bytesPerFrame)
                        writtenFrames += gap
                    }
                    writeBytes(audioTrack, clips[i].pcm)
                    writtenFrames += clips[i].pcm.size / bytesPerFrame
                }
            } catch (e: CancellationException) {
                // 已被 stop() 打断
            } catch (e: Exception) {
                // 写入失败：忽略，枪声时间戳照常返回（播放静音）
            }
        }

        // 用 AudioTimestamp 反推真实输出锚点，修正缓冲延迟
        var latencyMs = 0.0
        val timestamp = AudioTimestamp()
        val deadline = System.nanoTime() + 500_000_000L
        while (System.nanoTime() < deadline && generation.get() == token) {
            if (audioTrack.getTimestamp(timestamp) && timestamp.framePosition > 0) {
                latencyMs = MonotonicClock.fromNanos(timestamp.nanoTime) -
                    timestamp.framePosition * 1000.0 / sampleRate - anchor
                break
            }
            delay(10)
        }

        onCue("等待各就位…")
        val cues = listOf("各就位", "预备", "砰！")
        for (i in 0..2) {
            val remaining = times[i] + latencyMs - MonotonicClock.now()
            if (remaining > 0) delay(remaining.roundToLong())
            if (generation.get() != token) throw CancellationException()
            onCue(cues[i])
        }
        // 枪声输出时刻 = 规划时刻 + 缓冲延迟修正
        return Pair(times[2] + latencyMs, 20.0)
    }

    /** 非阻塞写循环：缓冲满时短暂让出，可被 stop() 及时取消。 */
    private suspend fun writeBytes(track: AudioTrack, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val written = track.write(data, offset, data.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            when {
                written > 0 -> offset += written
                written < 0 -> throw ClientError(0, null, "发令音频播放失败")
                else -> delay(4)
            }
        }
    }

    private suspend fun writeSilence(track: AudioTrack, frames: Long, bytesPerFrame: Int) {
        val chunk = ByteArray(SILENCE_CHUNK_BYTES)
        var remaining = frames * bytesPerFrame
        while (remaining > 0) {
            val size = minOf(chunk.size.toLong(), remaining).toInt()
            var offset = 0
            while (offset < size) {
                val written = track.write(chunk, offset, size - offset, AudioTrack.WRITE_NON_BLOCKING)
                when {
                    written > 0 -> offset += written
                    written < 0 -> throw ClientError(0, null, "发令音频播放失败")
                    else -> delay(4)
                }
            }
            remaining -= size
        }
    }

    /** 解析 res/raw 下的 16-bit PCM WAV。 */
    private fun loadWav(name: String): WavClip {
        val res = context.resources
        var id = res.getIdentifier(name, "raw", context.packageName)
        if (id == 0 && name.contains('_')) {
            // 文件名含连字符时资源名与 R 常量不同，回退原始文件名
            id = res.getIdentifier(name.replace('_', '-'), "raw", context.packageName)
        }
        if (id == 0) throw ClientError(0, null, "发令原声缺失")
        val bytes = res.openRawResource(id).use { it.readBytes() }
        if (bytes.size < 12 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") {
            throw ClientError(0, null, "发令原声缺失")
        }
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var pcm: ByteArray? = null
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = readIntLE(bytes, offset + 4)
            when (chunkId) {
                "fmt " -> {
                    channels = readShortLE(bytes, offset + 10)
                    sampleRate = readIntLE(bytes, offset + 12)
                    bits = readShortLE(bytes, offset + 22)
                }
                "data" -> pcm = bytes.copyOfRange(offset + 8, minOf(offset + 8 + chunkSize, bytes.size))
            }
            offset += 8 + chunkSize + (chunkSize % 2)
        }
        if (pcm == null || sampleRate <= 0 || channels <= 0 || bits != 16) {
            throw ClientError(0, null, "发令原声缺失")
        }
        val durationMs = pcm.size * 1000.0 / (sampleRate * channels * 2)
        return WavClip(sampleRate, channels, pcm, durationMs)
    }

    private fun readIntLE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readShortLE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private class WavClip(val sampleRate: Int, val channels: Int, val pcm: ByteArray, val durationMs: Double)

    private companion object {
        const val SILENCE_CHUNK_BYTES = 8192
    }
}
