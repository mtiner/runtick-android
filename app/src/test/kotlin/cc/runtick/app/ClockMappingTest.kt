package cc.runtick.app

import cc.runtick.app.core.ClientError
import cc.runtick.app.core.ClockMapping
import cc.runtick.app.core.ClockSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockMappingTest {

    // 手算样例：
    // 样本1: sent=0, received=10, serverReceived=5, serverSent=5.5
    //   rtt = 10 - 0 - (5.5 - 5) = 9.5；offset = ((5-0) + (5.5-10)) / 2 = 0.25
    // 样本2: sent=1000, received=1012, serverReceived=1005, serverSent=1005.5
    //   rtt = 12 - 0.5 = 11.5；offset = ((1005-1000) + (1005.5-1012)) / 2 = -0.75
    // 平均: rtt=10.5, offset=-0.25, 最大偏差=0.5, uncertainty = 10.5/2 + 0.5 + 1 = 6.75
    private val s1 = ClockSample(0.0, 10.0, 5.0, 5.5)
    private val s2 = ClockSample(1000.0, 1012.0, 1005.0, 1005.5)

    @Test
    fun `average 按公式精确汇总`() {
        val mapping = ClockMapping.average(listOf(s1, s2))
        assertEquals(-0.25, mapping.offsetMs, 1e-9)
        assertEquals(10.5, mapping.avgRttMs, 1e-9)
        assertEquals(6.75, mapping.uncertaintyMs, 1e-9)
        assertTrue(mapping.sampledAtMs > 0)
        // 新鲜度窗口 120 秒
        assertTrue(mapping.fresh)
    }

    @Test
    fun `空样本抛错`() {
        try {
            ClockMapping.average(emptyList())
            throw AssertionError("应当抛出 ClientError")
        } catch (e: ClientError) {
            assertEquals(0, e.status)
            assertEquals("网络延迟或波动过高，请重试校时", e.message)
        }
    }

    @Test
    fun `负 RTT 抛错`() {
        // serverSent 比 serverReceived 晚 90ms，RTT = 10 - 90 = -80
        val bad = ClockSample(0.0, 10.0, 0.0, 100.0)
        try {
            ClockMapping.average(listOf(bad))
            throw AssertionError("应当抛出 ClientError")
        } catch (e: ClientError) {
            assertEquals("网络延迟或波动过高，请重试校时", e.message)
        }
    }

    @Test
    fun `不确定度超过 250ms 抛错`() {
        // rtt = 1000 - 0 - 0 = 1000 → uncertainty = 500 + 0 + 1 > 250
        val slow = ClockSample(0.0, 1000.0, 500.0, 500.0)
        try {
            ClockMapping.average(listOf(slow))
            throw AssertionError("应当抛出 ClientError")
        } catch (e: ClientError) {
            assertEquals("网络延迟或波动过高，请重试校时", e.message)
        }
    }
}
