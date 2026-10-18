package cc.runtick.app

import cc.runtick.app.core.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NtpTest {
    @Test fun epochAndEra() {
        for (t in listOf(0.0,1700000000123.25,2085978495999.0,2085978496001.5,4000000000000.0)) {
            val bytes=ByteArray(48); NtpPacket.write(bytes,40,t)
            assertEquals(t,NtpPacket.timestamp(bytes,40,t),0.002)
        }
    }
    @Test fun packetValidationAndFormula() {
        val t=1700000000000.0
        val request=ByteArray(48); NtpPacket.write(request,40,t)
        val response=ByteArray(48); response[0]=0x24; response[1]=2
        request.copyInto(response,24,40,48)
        NtpPacket.write(response,32,t+6); NtpPacket.write(response,40,t+7)
        val sample=NtpPacket.parse(response,request,t,t+11)
        val result=ClockMapping.average(listOf(sample))
        assertEquals(1.0,result.offsetMs,0.002); assertEquals(10.0,result.avgRttMs,0.002)
        for (index in listOf(0,1,24,32,40)) {
            val bad=response.copyOf()
            when(index) { 0 -> bad[0]=0xe4.toByte(); 1 -> bad[1]=16; 24 -> bad[24]=(bad[24].toInt() xor 1).toByte(); else -> bad.fill(0,index,index+8) }
            assertThrows(Exception::class.java) { NtpPacket.parse(bad,request,t,t+11) }
        }
        assertThrows(Exception::class.java) { NtpPacket.parse(ByteArray(1),request,t,t+11) }
        response[1]=0; "RATE".toByteArray().copyInto(response,12)
        assertThrows(NtpPacket.Kiss::class.java) { NtpPacket.parse(response,request,t,t+11) }
    }
    @Test fun preferenceFallbackBlockedPort() = runTest {
        for (fails in 0..2) {
            val hosts=mutableListOf<String>()
            val client=NtpClient(NtpConfig(validSamples=3), exchange={ host ->
                hosts.add(host)
                if (fails==2 || (fails==1 && host=="ntp.tencent.com")) error("UDP blocked")
                ClockSample(0.0,10.0,6.0,6.0)
            },pause={})
            try {
                val result=client.synchronize()
                assertTrue(fails<2)
                assertEquals(if(fails==0) "ntp.tencent.com" else "ntp.aliyun.com",result.server)
            } catch(e: Exception) { assertEquals(2,fails) }
            assertEquals("ntp.tencent.com",hosts.first())
            if(fails==0) assertFalse(hosts.contains("ntp.aliyun.com"))
        }
    }
    @Test fun lockedMappingAndExpiry() {
        val local=MonotonicClock.now()
        val mapping=ClockMapping(100.0,3.0,2.0,local,referenceMono=MonotonicClock.nanosAt(local),referenceUTC=1700000000000.0)
        val next=mapping.copy(referenceUTC=9000000000000.0)
        assertEquals(1700000001234.5,mapping.utcAt(local+1234.5),0.002)
        assertNotEquals(mapping.utcAt(local),next.utcAt(local))
        assertFalse(mapping.copy(sampledAtMs=local-121000).fresh)
        assertFalse(mapping.copy(uncertaintyMs=24.0,sampledAtMs=local-30000).fresh)
    }

    @Test fun invalidResponseAndRetryPolicy() = runTest {
        var failures=0
        val client=NtpClient(NtpConfig(validSamples=3,consecutiveFailureLimit=2),exchange={ server ->
            if(server=="ntp.tencent.com") {
                failures++
                NtpPacket.parse(byteArrayOf(0),ByteArray(48),0.0,1.0)
            }
            ClockSample(0.0,10.0,6.0,6.0)
        },pause={})
        assertEquals("ntp.aliyun.com",client.synchronize().server)
        assertEquals(2,failures)
    }
    @Test fun interoperableRaceVectorAndQuality() {
        val json="""{"kind":"start","at":1700000000000.25,"uncertaintyMs":3,"monoNanos":"18446744073709550000","deviceId":"ios","sessionId":"round","syncServer":"ntp.tencent.com","syncSource":"NTP"}"""
        val event=AppJson.decodeFromString(TimingEvent.serializer(),json)
        assertEquals("18446744073709550000",event.monoNanos)
        assertEquals(10000.5,1700000010000.75-event.at,0.0)
        val mapping=NtpQuality.select(List(8) { ClockSample(0.0,10.0,6.0,6.0) })
        assertEquals(1.0,mapping.offsetMs,0.0); assertEquals(7.0005,mapping.uncertaintyMs,0.000001)
    }

    @Test fun deniedServerNotRetriedNextRound() = runTest {
        var denied=0
        val client=NtpClient(NtpConfig(validSamples=3),exchange={ host ->
            if(host=="ntp.tencent.com") { denied++; throw NtpPacket.Kiss("DENY",4) }
            ClockSample(0.0,10.0,6.0,6.0)
        },pause={})
        client.synchronize(); client.synchronize()
        assertEquals(1,denied)
    }
    @Test fun slowSampleQualityRejected() {
        assertThrows(Exception::class.java) {
            NtpQuality.select(List(8) { ClockSample(0.0,1000.0,500.0,500.0) })
        }
    }
}
