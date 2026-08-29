package com.leumas.robot.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineAssemblerTest {

    private fun LineAssembler.feed(s: String): List<String> = append(s.toByteArray(Charsets.ISO_8859_1))

    @Test
    fun `strips the CR that the firmware actually sends`() {
        // App/ble.c emits "MOVE:%u>%u\r\n" — CRLF, despite what the spec text says.
        val a = LineAssembler()
        assertEquals(listOf("MOVE:2>6"), a.feed("MOVE:2>6\r\n"))
    }

    @Test
    fun `reassembles a message split mid-token across packets`() {
        val a = LineAssembler()
        assertEquals(emptyList<String>(), a.feed("MOV"))
        assertEquals(listOf("MOVE:2>6"), a.feed("E:2>6\r\n"))
    }

    @Test
    fun `splits a burst delivered as one packet`() {
        // The firmware emits SEQ then both warm-macro RUNs inside one 25 ms tick.
        val a = LineAssembler()
        assertEquals(
            listOf("SEQ:1", "RUN:1", "RUN:2", "RUN:1", "RUN:3"),
            a.feed("SEQ:1\r\nRUN:1\r\nRUN:2\r\nRUN:1\r\nRUN:3\r\n"),
        )
    }

    @Test
    fun `handles a packet boundary landing between CR and LF`() {
        val a = LineAssembler()
        assertEquals(emptyList<String>(), a.feed("DONE:3\r"))
        assertEquals(listOf("DONE:3"), a.feed("\nRUN:4\r\n").let { listOf(it[0]) })
    }

    @Test
    fun `drops empty lines and bare terminators`() {
        val a = LineAssembler()
        assertEquals(listOf("READY"), a.feed("\r\n\r\nREADY\r\n\r\n"))
    }

    @Test
    fun `tolerates bare LF as well as CRLF`() {
        val a = LineAssembler()
        assertEquals(listOf("READY", "SEQ:2"), a.feed("READY\nSEQ:2\n"))
    }

    @Test
    fun `holds an unterminated tail until its newline arrives`() {
        val a = LineAssembler()
        assertEquals(listOf("RUN:1"), a.feed("RUN:1\r\nDON"))
        assertEquals(listOf("DONE:1"), a.feed("E:1\r\n"))
    }

    @Test
    fun `discards a runaway buffer rather than growing without bound`() {
        val a = LineAssembler(maxBufferedChars = 64)
        a.feed("x".repeat(200))
        assertEquals(1, a.overflowDiscards)
        // Still usable afterwards.
        assertEquals(listOf("READY"), a.feed("READY\r\n"))
    }

    @Test
    fun `reset drops a partial line`() {
        val a = LineAssembler()
        a.feed("MOV")
        a.reset()
        assertEquals(listOf("E:2>6"), a.feed("E:2>6\r\n"))
    }

    @Test
    fun `never throws on arbitrary binary input`() {
        val a = LineAssembler()
        val junk = ByteArray(256) { it.toByte() }
        repeat(20) { a.append(junk) }
        assertTrue(true)
    }
}
