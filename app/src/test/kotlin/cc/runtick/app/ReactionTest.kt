package cc.runtick.app
import cc.runtick.app.core.*
import org.junit.Assert.*
import org.junit.Test
class ReactionTest {
    @Test fun stableBaselineRejectsTransientSpikeAndCapturesFirstSustainedMotion() {
        val gate = MotionGate()
        gate.sample(0.0, 2.0, 1.0); assertNull(gate.movedAt)
        for (at in 100..700 step 10) gate.sample(at.toDouble(), 0.01, 0.01)
        assertTrue(gate.ready)
        gate.sample(710.0, 2.0, 1.0); gate.sample(720.0, 0.0, 0.0); assertNull(gate.movedAt)
        for (at in 800..840 step 10) gate.sample(at.toDouble(), 0.7, 0.5)
        assertEquals(800.0, gate.movedAt!!, 0.001)
    }
    @Test fun disabledMarksSurvivesSettingsPersistence() {
        val stage = StageDelay(false, 0.0, 0.0, enabled = false)
        val restored = AppJson.decodeFromString(StageDelay.serializer(), AppJson.encodeToString(StageDelay.serializer(), stage))
        assertFalse(restored.enabled); assertTrue(restored.valid)
    }
}
