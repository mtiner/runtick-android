package cc.runtick.app

import cc.runtick.app.core.RoleSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleSelectionTest {

    @Test
    fun `乐观选择立即生效且进入保存状态`() {
        val selection = RoleSelection()
        assertNull(selection.effective(null))
        selection.choose("start")
        assertEquals("start", selection.optimistic)
        assertTrue(selection.saving)
        // 服务端还没确认时，展示值已是乐观值
        assertEquals("start", selection.effective("finish"))
    }

    @Test
    fun `失败回滚乐观值`() {
        val selection = RoleSelection()
        selection.choose("start")
        selection.fail()
        assertNull(selection.optimistic)
        assertFalse(selection.saving)
        assertEquals("finish", selection.effective("finish"))
    }

    @Test
    fun `HTTP 先返回时 confirmServer 清掉乐观值`() {
        val selection = RoleSelection()
        selection.choose("start")
        selection.confirmServer("start")
        assertFalse(selection.saving)
        assertNull(selection.optimistic)
        assertEquals("start", selection.effective("start"))
    }

    @Test
    fun `socket state 先到时保留乐观值直至请求确认`() {
        val selection = RoleSelection()
        selection.choose("start")
        // saving 状态下收到房间状态不清乐观值
        selection.receivedServerState()
        assertEquals("start", selection.optimistic)
        assertTrue(selection.saving)
        // 请求成功确认后收敛
        selection.confirmServer("start")
        assertNull(selection.optimistic)
        // 非 saving 状态下收到房间状态以服务端为准
        selection.choose("finish")
        selection.fail()
        selection.receivedServerState()
        assertNull(selection.optimistic)
    }
}
