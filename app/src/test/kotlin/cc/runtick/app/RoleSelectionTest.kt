package cc.runtick.app
import cc.runtick.app.core.RoleSelection
import org.junit.Assert.*
import org.junit.Test

class RoleSelectionTest {
    @Test fun latestTapIsImmediateWhileWritesStaySerialized() {
        val s = RoleSelection()
        assertTrue(s.begin("start", "finish"))
        assertFalse(s.begin("finish", "finish"))
        assertEquals("finish", s.effective("start"))
        assertEquals("start", s.inFlight)
        s.succeeded("start")
        assertEquals("finish", s.inFlight)
        s.receivedServerState("start")
        assertEquals("finish", s.effective("start"))
        s.succeeded("start")
        assertFalse(s.saving)
        s.receivedServerState("start")
        assertEquals("finish", s.effective("start"))
        s.receivedServerState("finish")
        assertNull(s.optimistic)
    }
    @Test fun obsoleteFailureDoesNotRollBackNewerTap() {
        val s = RoleSelection()
        s.begin("start", "finish"); s.begin("finish", "finish")
        s.fail()
        assertEquals("finish", s.inFlight)
        assertEquals("finish", s.effective("start"))
        s.succeeded("finish")
        assertFalse(s.saving); assertNull(s.optimistic)
    }
    @Test fun latestFailureRollsBackAndRepeatedTapDoesNotStartAnotherWriter() {
        val s = RoleSelection()
        assertTrue(s.begin("start", "finish"))
        assertFalse(s.begin("start", "finish"))
        s.fail()
        assertFalse(s.saving); assertEquals("finish", s.effective("finish"))
    }
}
