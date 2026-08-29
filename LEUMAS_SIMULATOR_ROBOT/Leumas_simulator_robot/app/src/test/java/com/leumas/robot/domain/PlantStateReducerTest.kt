package com.leumas.robot.domain

import com.leumas.robot.protocol.LineAssembler
import com.leumas.robot.protocol.McuEvent
import com.leumas.robot.protocol.McuProtocolParser
import com.leumas.robot.protocol.TransferKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlantStateReducerTest {

    private fun reduce(state: PlantState, event: McuEvent, now: Long = 0L) =
        PlantStateReducer.reduce(state, event, now)

    /** Replay a CRLF wire string through the real assembler and parser. */
    private fun replay(wire: String): PlantState {
        val assembler = LineAssembler()
        return assembler.append(wire.toByteArray())
            .map(McuProtocolParser::parse)
            .fold(PlantState()) { acc, e -> reduce(acc, e) }
    }

    @Test
    fun `duplicate RUN for the same slot is idempotent`() {
        // The warm-macro path really does emit RUN:1 twice with no DONE between.
        val s = replay("SEQ:1\r\nRUN:1\r\nRUN:2\r\nRUN:1\r\nRUN:3\r\n")
        assertEquals(ModuleState.RUNNING, s.stateOf(1))
        assertEquals(listOf(1, 2, 3), s.runningSlots)
    }

    @Test
    fun `SEQ wipes all prior module state`() {
        var s = replay("SEQ:1\r\nRUN:1\r\nDONE:1\r\nRUN:4\r\n")
        assertEquals(ModuleState.DONE, s.stateOf(1))

        s = reduce(s, McuEvent.SequenceStarted(2))
        assertEquals(2, s.currentSequence)
        assertEquals(ModuleState.UNKNOWN, s.stateOf(1))
        assertEquals(ModuleState.UNKNOWN, s.stateOf(4))
        assertNull(s.currentTransfer)
        assertFalse(s.lotComplete)
        // READY is only sent once at boot, so a re-sequence must not un-ready the controller.
        assertTrue(s.controllerReady)
    }

    @Test
    fun `DONE does not retire a slot that is reused as a later MOVE source`() {
        // Gravimetric (3) finishes, then feeds Blender1 twice across two batches.
        var s = replay("SEQ:1\r\nRUN:3\r\nDONE:3\r\n")
        assertEquals(ModuleState.DONE, s.stateOf(3))
        s = reduce(s, McuEvent.Move(3, 4, TransferKind.ROBOT))
        assertEquals(ModuleState.TRANSFER_SOURCE, s.stateOf(3))
    }

    @Test
    fun `MOVE marks both endpoints and records the kind`() {
        val s = replay("SEQ:1\r\nMOVE:1>4\r\n")
        assertEquals(ModuleState.TRANSFER_SOURCE, s.stateOf(1))
        assertEquals(ModuleState.TRANSFER_DESTINATION, s.stateOf(4))
        assertEquals(TransferKind.ROBOT, s.currentTransfer?.kind)
    }

    @Test
    fun `blender to packaging is recorded as a process transfer not a robot one`() {
        val s = replay("SEQ:1\r\nMOVE:4>6\r\n")
        assertEquals(TransferKind.PROCESS, s.currentTransfer?.kind)
        assertTrue(s.operationText.startsWith("PROCESS TRANSFER"))
    }

    @Test
    fun `LOT DONE clears the transfer and reports completion`() {
        val s = replay("SEQ:1\r\nMOVE:4>6\r\nLOT:DONE\r\n")
        assertTrue(s.lotComplete)
        assertNull(s.currentTransfer)
        assertEquals("LOT COMPLETE", s.operationText)
    }

    @Test
    fun `unknown messages leave the state untouched`() {
        val before = replay("SEQ:1\r\nRUN:1\r\n")
        val after = reduce(before, McuEvent.Unknown("INVALID DATA", "unrecognised message"))
        assertEquals(before, after)
    }

    @Test
    fun `full seq 1 batch A trace ends with packaging done`() {
        val s = replay(
            "SEQ:1\r\nRUN:1\r\nRUN:2\r\nRUN:1\r\nRUN:3\r\n" +
                "DONE:1\r\nDONE:3\r\nDONE:2\r\n" +
                "MOVE:1>4\r\nMOVE:3>4\r\nRUN:4\r\nDONE:4\r\n" +
                "MOVE:4>6\r\nRUN:6\r\nDONE:6\r\nLOT:DONE\r\n"
        )
        assertEquals(1, s.currentSequence)
        assertEquals(ModuleState.DONE, s.stateOf(6))
        assertTrue(s.lotComplete)
    }
}
