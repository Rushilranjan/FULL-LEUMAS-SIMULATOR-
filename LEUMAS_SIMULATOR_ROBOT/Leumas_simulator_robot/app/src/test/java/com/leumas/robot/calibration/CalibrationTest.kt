package com.leumas.robot.calibration

import com.leumas.robot.protocol.SlotMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationTest {

    @Test
    fun `defaults cover all eleven firmware slots`() {
        // Seq 4/5 emit RUN:11 and MOVE:8>10, so 9 entries would not be enough.
        (SlotMap.MIN_SLOT..SlotMap.MAX_SLOT).forEach {
            assertTrue("slot $it missing a default", Calibration.DEFAULT.points.containsKey(it))
        }
    }

    @Test
    fun `defaults are already complete for the nine calibratable modules`() {
        assertTrue(Calibration.DEFAULT.isComplete)
        assertEquals(9, SlotMap.calibratable.size)
    }

    @Test
    fun `packaging and bottling are the two special stations`() {
        val special = SlotMap.all.filter { it.isSpecialStation }.map { it.slot }
        assertEquals(listOf(6, 11), special)
    }

    @Test
    fun `edge points map onto the correct boundary`() {
        assertEquals(0f to 0.25f, EdgePoint(ArenaEdge.LEFT, 0.25f).toNormalized())
        assertEquals(1f to 0.25f, EdgePoint(ArenaEdge.RIGHT, 0.25f).toNormalized())
        assertEquals(0.6f to 0f, EdgePoint(ArenaEdge.TOP, 0.6f).toNormalized())
        assertEquals(0.6f to 1f, EdgePoint(ArenaEdge.BOTTOM, 0.6f).toNormalized())
    }

    @Test
    fun `snapping picks the nearest edge and keeps the offset along it`() {
        assertEquals(EdgePoint(ArenaEdge.TOP, 0.5f), EdgePoint.snapToNearestEdge(0.5f, 0.05f))
        assertEquals(EdgePoint(ArenaEdge.BOTTOM, 0.5f), EdgePoint.snapToNearestEdge(0.5f, 0.95f))
        assertEquals(EdgePoint(ArenaEdge.LEFT, 0.4f), EdgePoint.snapToNearestEdge(0.03f, 0.4f))
        assertEquals(EdgePoint(ArenaEdge.RIGHT, 0.4f), EdgePoint.snapToNearestEdge(0.97f, 0.4f))
    }

    @Test
    fun `snapping clamps taps from outside the arena`() {
        val p = EdgePoint.snapToNearestEdge(-0.4f, 1.9f)
        assertTrue(p.position in 0f..1f)
    }

    @Test
    fun `unknown slots are rejected rather than stored`() {
        val c = Calibration().with(99, EdgePoint(ArenaEdge.TOP, 0.5f))
        assertTrue(c.points.isEmpty())
    }

    @Test
    fun `an uncalibrated slot falls back to its default`() {
        assertEquals(Calibration.DEFAULT.pointFor(4), Calibration().pointFor(4))
    }
}
