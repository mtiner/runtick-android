package cc.runtick.app

import cc.runtick.app.core.AppJson
import cc.runtick.app.core.AppVersionInfo
import cc.runtick.app.core.ClientError
import cc.runtick.app.core.decodeVersionInfo
import cc.runtick.app.core.evaluateUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UpdateCheckTest {

    private fun info(
        versionCode: Int,
        minVersionCode: Int = 1,
        forceUpdate: Boolean = false,
    ) = AppVersionInfo(
        platform = "android",
        versionName = "1.2.0",
        versionCode = versionCode,
        minVersionCode = minVersionCode,
        forceUpdate = forceUpdate,
        notes = "修复若干问题",
        downloadUrl = "https://download.runtick.cc/app.apk",
        releasedAt = 1792000000000,
    )

    // ---------- evaluateUpdate ----------

    @Test
    fun `服务器版本更高时提示更新`() {
        val decision = evaluateUpdate(1, info(versionCode = 2))!!
        assertTrue(decision.info.versionCode > decision.currentVersionCode)
        assertFalse(decision.force)
    }

    @Test
    fun `同版本或更旧版本不提示`() {
        assertNull(evaluateUpdate(3, info(versionCode = 3)))
        assertNull(evaluateUpdate(5, info(versionCode = 2)))
    }

    @Test
    fun `forceUpdate 标记触发强制更新`() {
        val decision = evaluateUpdate(1, info(versionCode = 2, forceUpdate = true))!!
        assertTrue(decision.force)
    }

    @Test
    fun `低于 minVersionCode 触发强制更新`() {
        val decision = evaluateUpdate(1, info(versionCode = 2, minVersionCode = 2))!!
        assertTrue(decision.force)
    }

    @Test
    fun `满足 minVersionCode 且无标记则非强制`() {
        val decision = evaluateUpdate(2, info(versionCode = 3, minVersionCode = 2))!!
        assertFalse(decision.force)
    }

    @Test
    fun `forceUpdate 与 minVersionCode 任一满足即强制`() {
        val byFlag = evaluateUpdate(2, info(versionCode = 3, minVersionCode = 1, forceUpdate = true))!!
        val byMin = evaluateUpdate(2, info(versionCode = 3, minVersionCode = 3))!!
        assertTrue(byFlag.force)
        assertTrue(byMin.force)
    }

    // ---------- decodeVersionInfo ----------

    @Test
    fun `版本信息解码完整字段`() {
        val data = AppJson.parseToJsonElement(
            """{"platform":"android","versionName":"1.2.0","versionCode":2,
               "minVersionCode":1,"forceUpdate":false,"notes":"修复若干问题",
               "downloadUrl":"https://download.runtick.cc/app.apk","releasedAt":1792000000000}"""
        )
        val decoded = decodeVersionInfo(data)
        assertEquals("android", decoded.platform)
        assertEquals("1.2.0", decoded.versionName)
        assertEquals(2, decoded.versionCode)
        assertEquals(1, decoded.minVersionCode)
        assertFalse(decoded.forceUpdate)
        assertEquals("修复若干问题", decoded.notes)
        assertEquals("https://download.runtick.cc/app.apk", decoded.downloadUrl)
        assertEquals(1792000000000L, decoded.releasedAt)
    }

    @Test
    fun `版本信息可选字段缺省`() {
        val data = AppJson.parseToJsonElement(
            """{"platform":"android","versionName":"1.0.0","versionCode":1}"""
        )
        val decoded = decodeVersionInfo(data)
        assertEquals(1, decoded.minVersionCode)
        assertFalse(decoded.forceUpdate)
        assertNull(decoded.notes)
        assertNull(decoded.downloadUrl)
        assertNull(decoded.releasedAt)
    }

    @Test
    fun `未知字段被忽略`() {
        val data = AppJson.parseToJsonElement(
            """{"platform":"android","versionName":"1.0.0","versionCode":1,"extra":true}"""
        )
        assertEquals("1.0.0", decodeVersionInfo(data).versionName)
    }

    @Test
    fun `版本信息格式无效抛出 ClientError`() {
        val data = AppJson.parseToJsonElement("""{"platform":"android"}""")
        try {
            decodeVersionInfo(data)
            fail("应抛出 ClientError")
        } catch (e: ClientError) {
            assertEquals(0, e.status)
        }
    }
}
