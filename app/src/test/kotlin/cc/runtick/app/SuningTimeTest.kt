package cc.runtick.app

import cc.runtick.app.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SuningTimeTest {
    private val json = """{"api":"time","code":"1","currentTime":1792384756200,"msg":""}"""
    @Test fun validatesProvidedResponseAndRejectsCachedOrSecondsOnlyTime() {
        assertEquals(1792384756200.0,SuningTimeProtocol.timestamp(json,200),0.0)
        assertEquals(1792384756200.0,SuningTimeProtocol.timestamp(json,200,"0"),0.0)
        for(data in listOf(json.replace("\"1\"","\"0\""),json.replace("1792384756200","1792384756"),json.replace("\"time\"","\"other\""),"{}")) {
            assertTrue(runCatching { SuningTimeProtocol.timestamp(data,200) }.isFailure)
        }
        assertTrue(runCatching { SuningTimeProtocol.timestamp(json,503) }.isFailure)
        assertTrue(runCatching { SuningTimeProtocol.timestamp(json,200,"10") }.isFailure)
    }
    @Test fun midpointOffsetMappingAndHTTPSourceMatchIOSVector() {
        val t=1792384756000.0
        val samples=(0..3).map { HTTPTimeSample(t+it*100,t+it*100+10,t+it*100+205) }
        val mapping=SuningTimeProtocol.select(samples,SuningTimeConfig(validSamples=4))
        assertEquals(200.0,mapping.offsetMs,0.0)
        assertEquals(10.0,mapping.avgRttMs,0.0)
        assertEquals(30.0155,mapping.uncertaintyMs,0.00001)
        assertEquals("HTTP",mapping.syncSource); assertEquals("f.m.suning.com",mapping.server)
        assertEquals(4,mapping.validSamples); assertTrue(mapping.fresh)
        val local=MonotonicClock.now()
        assertEquals(1234.5,mapping.utcAt(local+1234.5)-mapping.utcAt(local),0.002)
    }
    @Test fun rejectsSlowUnstableAndInsufficientSamples() {
        val good=HTTPTimeSample(0.0,10.0,205.0)
        for(samples in listOf(listOf(good),List(4) { HTTPTimeSample(0.0,500.0,205.0) },listOf(good,good,good,HTTPTimeSample(0.0,10.0,405.0)))) {
            assertTrue(runCatching { SuningTimeProtocol.select(samples,SuningTimeConfig()) }.isFailure)
        }
    }
    @Test fun collectsEightSamplesAndBoundsTimeoutRetries() = runBlocking {
        var count=0
        val good=SuningTimeClient(exchange={ count++; val sent=1792384756000.0+count*100; HTTPTimeSample(sent,sent+10,sent+205) },pause={ error("Zero interval must not schedule a pause") })
        assertEquals(8,good.synchronize().validSamples); assertEquals(8,count)
        val outcomes=mutableListOf<String>(); count=0
        val failed=SuningTimeClient(exchange={ count++; error("HTTP 请求超时") },pause={})
        assertTrue(runCatching { failed.synchronize { _,message->outcomes.add(message) } }.isFailure)
        assertEquals(12,count); assertTrue(outcomes.any { it.contains("HTTP 请求超时") })
        assertEquals(AppTimeSync.Source.SUNING_HTTP,AppTimeSync.source)
    }
    @Test fun repeatedServerTimestampCannotCompleteSynchronization() = runBlocking {
        val frozen=SuningTimeClient(exchange={ HTTPTimeSample(0.0,10.0,1792384756200.0) },pause={})
        assertTrue(runCatching { frozen.synchronize() }.isFailure)
    }

    @Test fun prefersLowRTTHalfInsteadOfAveragingAllOffsets() {
        val samples=(0..7).map { n ->
            val sent=1792384756000.0+n*100
            val rtt=if(n<4) 10.0 else 100.0
            val offset=if(n<4) 200.0 else 220.0
            HTTPTimeSample(sent,sent+rtt,sent+rtt/2+offset)
        }
        val result=SuningTimeProtocol.select(samples,SuningTimeConfig())
        assertEquals(8,result.validSamples)
        assertEquals(200.0,result.offsetMs,0.0)
        assertEquals(10.0,result.avgRttMs,0.0)
    }
    @Test fun retryAfterSupportsSecondsDateAndDefault() {
        assertEquals(15000.0,SuningTimeProtocol.retryDelayMs(429,"15")!!,0.0)
        assertEquals(10000.0,SuningTimeProtocol.retryDelayMs(503,"Thu, 01 Jan 1970 00:00:10 GMT",0)!!,0.0)
        assertEquals(60000.0,SuningTimeProtocol.retryDelayMs(429,"invalid")!!,0.0)
        assertNull(SuningTimeProtocol.retryDelayMs(200,"15"))
    }
    @Test fun rateLimitStopsRoundAndBlocksImmediateRetry() = runBlocking {
        var count=0
        val client=SuningTimeClient(exchange={ count++; throw HTTPTimeRateLimit(60000.0) })
        repeat(2) {
            val error=runCatching { client.synchronize() }.exceptionOrNull()
            assertTrue(error is HTTPTimeRateLimit)
        }
        assertEquals(1,count)
    }
}
