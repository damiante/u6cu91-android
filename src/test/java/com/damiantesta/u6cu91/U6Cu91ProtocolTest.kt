package com.damiantesta.u6cu91

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the reverse-engineered frames against the exact bytes emitted by the vendor DLL
 * (`E7umf.dll`). Each expected value below was read out of the disassembly of the
 * corresponding `uhf_*` export; see this package's README for the derivation.
 */
class U6Cu91ProtocolTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `identify frame matches uhf_connect handshake`() {
        // 0x1330: LEN=2, payload [0x02, 0xA1]
        assertArrayEquals(bytes(0x02, 0x02, 0xA1), U6Cu91Protocol.identify())
    }

    @Test
    fun `action frame matches uhf_action`() {
        // 0x1d50: LEN=4, payload [0x02, 0x91, action, time]; beep+green led, 500ms -> 50 units
        val bits = U6Cu91Protocol.ACTION_BEEP or U6Cu91Protocol.ACTION_GREEN_LED
        assertArrayEquals(bytes(0x04, 0x02, 0x91, 0x05, 0x32), U6Cu91Protocol.action(bits, 50))
    }

    @Test
    fun `streaming inventory frame matches uhf_inventory`() {
        // 0x2020: LEN=2, payload ['U', CR]
        assertArrayEquals(bytes(0x02, 0x55, 0x0D), U6Cu91Protocol.inventoryStream())
    }

    @Test
    fun `paged inventory frames match uhf_inventory else-branch`() {
        // 0x21fa: LEN=3, [0x02, 'U', 0x80]; loop rebuilds [0x02, 'U', 0x91]
        assertArrayEquals(bytes(0x03, 0x02, 0x55, 0x80), U6Cu91Protocol.inventoryCount())
        assertArrayEquals(bytes(0x03, 0x02, 0x55, 0x91), U6Cu91Protocol.inventoryNext())
    }

    @Test
    fun `read EPC frame matches uhf_read example`() {
        // C# example: uhf_read(icdev, 1, 0, 8) -> read EPC, addr 0, 8 words.
        // 0x1000: LEN=8, [0x02, 'A', 'R', '1', ',', '0', ',', '8']
        assertArrayEquals(
            bytes(0x08, 0x02, 0x41, 0x52, 0x31, 0x2C, 0x30, 0x2C, 0x38),
            U6Cu91Protocol.read(infoType = 1, address = 0, words = 8),
        )
    }

    @Test
    fun `read frame uses two hex digits for addresses over 15`() {
        // 0x107d branch: address >= 0x10 -> two uppercase hex digits, LEN becomes 9.
        assertArrayEquals(
            bytes(0x09, 0x02, 0x41, 0x52, 0x33, 0x2C, 0x41, 0x42, 0x2C, 0x34),
            U6Cu91Protocol.read(infoType = 3, address = 0xAB, words = 4),
        )
    }

    @Test
    fun `write frame matches uhf_write layout`() {
        // 0x1150: LEN = words*4 + 9, [0x02,'A','W',type,',',addr,',',len,',',data...]
        // Write 1 word (2 bytes) of 0x11 0x22 to EPC at address 2.
        val data = U6Cu91Protocol.bytesToAsciiHex(bytes(0x11, 0x22)) // "1122"
        val expected = bytes(
            0x0D, // LEN = 1*4 + 9
            0x02, 0x41, 0x57, 0x31, 0x2C, 0x32, 0x2C, 0x31, 0x2C,
            0x31, 0x31, 0x32, 0x32, // "1122"
        )
        assertArrayEquals(expected, U6Cu91Protocol.write(infoType = 1, address = 2, words = 1, data))
    }

    @Test
    fun `parse extracts frame payload and status`() {
        // action success response observed in DLL: [03][02][91][00]
        val response = U6Cu91Protocol.parse(bytes(0x03, 0x02, 0x91, 0x00, 0x00, 0x00))!!
        assertEquals(3, response.length)
        assertEquals(U6Cu91Protocol.STATUS_OK, response.status)
        assertTrue(U6Cu91Protocol.isAck(response))
    }

    @Test
    fun `parse rejects malformed reports`() {
        assertNull(U6Cu91Protocol.parse(ByteArray(0)))
        assertNull(U6Cu91Protocol.parse(bytes(0x00)))          // zero length
        assertNull(U6Cu91Protocol.parse(bytes(0x40, 0x01)))    // length exceeds report
    }

    @Test
    fun `readData returns ascii hex payload of a read response`() {
        // Response shape [0x02, 'A', status, 'R', <ascii hex data>]
        val response = U6Cu91Protocol.parse(
            bytes(0x07, 0x02, 0x41, 0x00, 0x52, 0x41, 0x42, 0x43)
        )!!
        assertEquals("ABC", U6Cu91Protocol.readData(response))
    }

    @Test
    fun `readData rejects a non-ok status`() {
        val response = U6Cu91Protocol.parse(
            bytes(0x07, 0x02, 0x41, 0x01, 0x52, 0x41, 0x42, 0x43)
        )!!
        assertNull(U6Cu91Protocol.readData(response))
    }

    @Test
    fun `parseInventory splits CRLF-delimited epc lines`() {
        val body = "E20000123456\r\nE20000ABCDEF\r\n".toByteArray(Charsets.US_ASCII)
        assertEquals(listOf("E20000123456", "E20000ABCDEF"), U6Cu91Protocol.parseInventory(body))
    }

    @Test
    fun `parseInventory ignores empty and non-hex content`() {
        val body = "\r\nNOTAG\r\n".toByteArray(Charsets.US_ASCII)
        // "NOTAG" has hex-looking chars A and D but the longest run is too short to be an EPC.
        assertTrue(U6Cu91Protocol.parseInventory(body).all { it.length >= 4 })
    }

    @Test
    fun `hex round trips`() {
        val raw = bytes(0x00, 0x11, 0xAB, 0xFF)
        assertArrayEquals("0011ABFF".toByteArray(), U6Cu91Protocol.bytesToAsciiHex(raw))
        assertArrayEquals(raw, U6Cu91Protocol.asciiHexToBytes("0011abff"))
    }

    @Test
    fun `isAck is false without a status byte`() {
        val response = U6Cu91Protocol.parse(bytes(0x02, 0x02, 0xA1))!!
        assertFalse(U6Cu91Protocol.isAck(response))
    }

    @Test
    fun `parsePagedCount reads the count byte from a real capture`() {
        // Captured from a FONGWAH U6-CU-91 with one tag present: "06 02 55 80 01 00 1F".
        val response = U6Cu91Protocol.parse(bytes(0x06, 0x02, 0x55, 0x80, 0x01, 0x00, 0x1F))
        assertEquals(1, U6Cu91Protocol.parsePagedCount(response))
    }

    @Test
    fun `parsePagedCount is zero with no tag`() {
        val response = U6Cu91Protocol.parse(bytes(0x06, 0x02, 0x55, 0x80, 0x00, 0x00, 0x1F))
        assertEquals(0, U6Cu91Protocol.parsePagedCount(response))
    }

    @Test
    fun `parsePagedTag decodes the EPC from a real capture`() {
        // Real per-tag response (report LEN=0x23): PC word + 12-byte EPC + CRC + varying tail.
        val report = bytes(
            0x23, 0x02, 0x55, 0x91, 0x01, 0xA0, 0x1D, 0x00, 0x91, 0x10, 0x34, 0x00,
            0xE2, 0x80, 0xF3, 0x02, 0x00, 0x00, 0x00, 0x01, 0x50, 0x6D, 0xC0, 0xA4,
            0xCF, 0x7E, 0xE6, 0x0E, 0xAD, 0x0B, 0x0D, 0xD7, 0xF2, 0x01, 0x09, 0x1C,
        )
        assertEquals("E280F30200000001506DC0A4", U6Cu91Protocol.parsePagedTag(U6Cu91Protocol.parse(report)))
    }

    @Test
    fun `parsePagedTag is stable across a varying tail`() {
        // Same physical tag, different trailing RSSI/timestamp bytes -> identical EPC.
        val head = intArrayOf(
            0x23, 0x02, 0x55, 0x91, 0x01, 0xA0, 0x1D, 0x00, 0x91, 0x10, 0x34, 0x00,
            0xE2, 0x80, 0xF3, 0x02, 0x00, 0x00, 0x00, 0x01, 0x50, 0x6D, 0xC0, 0xA4,
            0xCF, 0x7E, 0xE6,
        )
        val r1 = U6Cu91Protocol.parse(bytes(*(head + intArrayOf(0x0E, 0xAD, 0x0B, 0x0D, 0xD7, 0xF2, 0x01, 0x09, 0x1C))))
        val r2 = U6Cu91Protocol.parse(bytes(*(head + intArrayOf(0x0F, 0x17, 0x59, 0x0D, 0xD7, 0xF2, 0x01, 0x0A, 0x62))))
        assertEquals(U6Cu91Protocol.parsePagedTag(r1), U6Cu91Protocol.parsePagedTag(r2))
    }

    @Test
    fun `parsePagedTag rejects a record without the 0xA0 marker`() {
        val response = U6Cu91Protocol.parse(bytes(0x08, 0x02, 0x55, 0x91, 0x01, 0x00, 0x1D, 0x00, 0x91))
        assertNull(U6Cu91Protocol.parsePagedTag(response))
    }
}
