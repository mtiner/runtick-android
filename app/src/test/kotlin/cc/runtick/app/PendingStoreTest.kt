package cc.runtick.app

import cc.runtick.app.core.PendingEvent
import cc.runtick.app.core.TimingEvent
import cc.runtick.app.services.MemoryPendingStore
import org.junit.Assert.*
import org.junit.Test

class PendingStoreTest {
    @Test fun lateAcknowledgementCannotRemoveANewerEvent() {
        val store = MemoryPendingStore()
        val old = PendingEvent("1234", "old", 1, TimingEvent("finish", 1000.0, 2.0))
        val new = old.copy(raceId = "new")
        store.save("pending", old); store.save("pending", new)
        store.deleteIfUnchanged("pending", old)
        assertEquals(new, store.load("pending"))
        store.deleteIfUnchanged("pending", new)
        assertNull(store.load("pending"))
    }
}
