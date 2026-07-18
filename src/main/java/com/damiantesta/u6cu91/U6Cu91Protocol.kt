package com.damiantesta.u6cu91

/**
 * Wire protocol for the U6-CU-91 UHF reader, reverse-engineered from the vendor's
 * Windows DLL (`E7umf.dll`, exports `uhf_*`). See `README.md` in this package for the
 * full derivation. This object is pure (no Android/USB dependencies) so it can be unit
 * tested against the exact byte frames recovered from the DLL.
 *
 * ## Transport
 * The reader is a USB HID device (vendor id [VENDOR_ID]). Every command and response is
 * carried in fixed [REPORT_SIZE]-byte HID reports over the interrupt endpoints; the DLL
 * always writes and reads 64 bytes (`WriteFile`/`ReadFile` with length 0x40).
 *
 * ## Frame format
 * A frame is `[LEN][payload...]`, where `LEN` is the number of payload bytes that follow
 * (it does not count itself). The transport zero-pads the frame out to [REPORT_SIZE].
 *
 * Requests use payloads that begin with the class byte [CLASS] (0x02) followed by a
 * sub-command, except inventory which uses the bare `'U'` command. The reader speaks
 * ASCII: numeric parameters and tag data are sent as ASCII-hex text, which is why the
 * DLL ships `a_hex`/`hex_a` helpers. Responses are `[LEN][0x02][sub][status][data...]`,
 * where `status == 0x00` means success.
 */
internal object U6Cu91Protocol {

    /** USB vendor id the DLL filters HID device paths by (`vid_0e6a`). PID is not filtered. */
    const val VENDOR_ID = 0x0E6A

    /** Every HID report is exactly this many bytes in both directions. */
    const val REPORT_SIZE = 64

    /** Command class byte prefixing most request payloads. */
    private const val CLASS = 0x02

    /** Sub-command bytes. */
    private const val SUB_IDENTIFY = 0xA1
    private const val SUB_ACTION = 0x91
    private const val CMD_INVENTORY = 0x55           // 'U'
    private const val LETTER_A = 0x41                // 'A' — prefix of the read/write/lock/kill/pwd family
    private const val LETTER_READ = 0x52             // 'R'
    private const val LETTER_WRITE = 0x57            // 'W'
    private const val COMMA = 0x2C                   // ','
    private const val CR = 0x0D
    private const val LF = 0x0A

    const val STATUS_OK = 0x00

    /** Marker byte (`payload[4]`) that identifies a valid paged tag record. */
    private const val PAGED_TAG_MARKER = 0xA0

    /** `uhf_action` bit flags; a request may OR several together. */
    const val ACTION_BEEP = 0x01
    const val ACTION_RED_LED = 0x02
    const val ACTION_GREEN_LED = 0x04
    const val ACTION_YELLOW_LED = 0x08

    // ---------------------------------------------------------------------------------------------
    // Request builders — each returns the full frame ([LEN] + payload); the transport pads to 64.
    // ---------------------------------------------------------------------------------------------

    /** Version/identify probe: `[02][02][A1]`. The DLL sends this to recognise the reader. */
    fun identify(): ByteArray = frame(CLASS, SUB_IDENTIFY)

    /**
     * Buzzer/LED control ([ACTION_BEEP] etc. OR-ed into [actionBits], [durationUnits] in 10ms
     * units): `[04][02][91][bits][time]`.
     */
    fun action(actionBits: Int, durationUnits: Int): ByteArray =
        frame(CLASS, SUB_ACTION, actionBits and 0xFF, durationUnits and 0xFF)

    /** Streaming inventory used by "W"-class readers: `[02]['U'][CR]`. */
    fun inventoryStream(): ByteArray = frame(CMD_INVENTORY, CR)

    /** Paged-inventory tag count query: `[03][02]['U'][80]`. */
    fun inventoryCount(): ByteArray = frame(CLASS, CMD_INVENTORY, 0x80)

    /** Paged-inventory next-tag read: `[03][02]['U'][91]`. */
    fun inventoryNext(): ByteArray = frame(CLASS, CMD_INVENTORY, SUB_ACTION)

    /**
     * Read [words] words (4 ASCII-hex chars each) from memory [infoType] starting at [address]:
     * `[LEN][02]'A''R'<type>,<addr>,<len>`. Numbers are uppercase ASCII hex; [address] is one hex
     * digit when < 16, otherwise two; [words] is a single hex digit (so at most 15).
     */
    fun read(infoType: Int, address: Int, words: Int): ByteArray {
        val payload = ArrayList<Int>(10)
        payload += CLASS
        payload += LETTER_A
        payload += LETTER_READ
        payload += asciiDigit(infoType)          // matches DLL: infoType + '0'
        payload += COMMA
        payload += addressBytes(address)
        payload += COMMA
        payload += hexNibble(words)
        return frame(payload)
    }

    /**
     * Write [asciiHexData] (already ASCII-hex, [words]*4 bytes) to memory [infoType] at [address]:
     * `[LEN][02]'A''W'<type>,<addr>,<len>,<data>`.
     */
    fun write(infoType: Int, address: Int, words: Int, asciiHexData: ByteArray): ByteArray {
        val payload = ArrayList<Int>(12 + asciiHexData.size)
        payload += CLASS
        payload += LETTER_A
        payload += LETTER_WRITE
        payload += asciiDigit(infoType)
        payload += COMMA
        payload += addressBytes(address)
        payload += COMMA
        payload += hexNibble(words)
        payload += COMMA
        asciiHexData.forEach { payload += it.toInt() and 0xFF }
        return frame(payload)
    }

    // ---------------------------------------------------------------------------------------------
    // Response parsing
    // ---------------------------------------------------------------------------------------------

    /**
     * A parsed response frame. [status] is the byte the reader places after the echoed
     * command bytes; [payload] is everything after `LEN` (i.e. `[0x02][sub]...`).
     */
    data class Response(val payload: ByteArray) {
        val length: Int get() = payload.size
        /** Status byte for `[02][sub][status]...` responses; null if the frame is too short. */
        val status: Int? get() = payload.getOrNull(2)?.let { it.toInt() and 0xFF }
    }

    /** Splits a received report into its `[LEN][payload...]` frame. Returns null if malformed. */
    fun parse(report: ByteArray): Response? {
        if (report.isEmpty()) return null
        val len = report[0].toInt() and 0xFF
        if (len == 0 || len >= report.size) return null
        return Response(report.copyOfRange(1, 1 + len))
    }

    /** True when [response] echoes success for a simple `[02][sub][00]` acknowledgement. */
    fun isAck(response: Response): Boolean = response.status == STATUS_OK

    /**
     * Extracts the ASCII-hex data payload of a read response `[02]'A'<status>'R'<data...>`.
     * Returns null if the frame is not a successful read.
     */
    fun readData(response: Response): String? {
        val p = response.payload
        // p[0]=0x02, p[1]='A', p[2]=status, p[3]='R', p[4..]=data
        if (p.size < 4) return null
        if ((p[1].toInt() and 0xFF) != LETTER_A) return null
        if ((p[2].toInt() and 0xFF) != STATUS_OK) return null
        if ((p[3].toInt() and 0xFF) != LETTER_READ) return null
        return String(p, 4, p.size - 4, Charsets.US_ASCII)
    }

    /** Tag count from a paged `[02]'U'[80]` response (`payload[3]`); 0 if unreadable. */
    fun parsePagedCount(response: Response?): Int {
        val p = response?.payload ?: return 0
        return if (p.size < 4) 0 else p[3].toInt() and 0xFF
    }

    /**
     * EPC hex string from a paged per-tag `[02]'U'[91]` response, or null.
     *
     * Layout confirmed from a FONGWAH U6-CU-91 capture (see `u6cu91-linux/README.md`):
     * `[02]'U'[91][index][A0][recLen][..][epcField]`. `payload[4]` is the 0xA0 tag marker,
     * `payload[8]` is the EPC-bank read length (PC word + EPC + CRC), and the EPC is the
     * `payload[8] - 4` bytes starting at `payload[11]` (skipping the PC word, dropping the CRC).
     * The EPC arrives as raw bytes here, so we hex-encode it; the trailing RSSI/timestamp bytes
     * vary per read and are excluded, so the same tag yields a stable EPC.
     */
    fun parsePagedTag(response: Response?): String? {
        val p = response?.payload ?: return null
        if (p.size < 9) return null
        if ((p[4].toInt() and 0xFF) != PAGED_TAG_MARKER) return null
        val epcLen = (p[8].toInt() and 0xFF) - 4
        val start = 11
        if (epcLen <= 0 || start + epcLen > p.size) return null
        return buildString(epcLen * 2) {
            for (i in start until start + epcLen) {
                val v = p[i].toInt() and 0xFF
                append(HEX[v ushr 4]); append(HEX[v and 0x0F])
            }
        }
    }

    /**
     * Parses a streaming-inventory response body (the CR/LF-delimited text a "W"-class reader
     * streams after `[02]'U'CR`) into EPC hex strings. The U6-CU-91 V0224 uses the paged path
     * ([parsePagedCount]/[parsePagedTag]) instead; this remains for the streaming variant.
     */
    fun parseInventory(body: ByteArray): List<String> {
        val text = String(body, Charsets.US_ASCII)
        val epcs = ArrayList<String>()
        for (rawLine in text.split("\r\n", "\n", "\r")) {
            val epc = longestHexRun(rawLine) ?: continue
            if (epc.length >= 4) epcs += epc.uppercase()
        }
        return epcs
    }

    // ---------------------------------------------------------------------------------------------
    // Hex helpers (equivalents of the DLL's a_hex / hex_a exports)
    // ---------------------------------------------------------------------------------------------

    private val HEX = "0123456789ABCDEF".toCharArray()

    /** Bytes -> uppercase ASCII-hex bytes, as `uhf_write` expects its data pre-encoded. */
    fun bytesToAsciiHex(data: ByteArray): ByteArray {
        val out = ByteArray(data.size * 2)
        for (i in data.indices) {
            val v = data[i].toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4].code.toByte()
            out[i * 2 + 1] = HEX[v and 0x0F].code.toByte()
        }
        return out
    }

    /** ASCII-hex text -> bytes; ignores any trailing odd nibble and non-hex characters. */
    fun asciiHexToBytes(hex: String): ByteArray {
        val digits = hex.filter { it.isHexDigit() }
        val out = ByteArray(digits.length / 2)
        for (i in out.indices) {
            out[i] = ((digits[i * 2].hexValue() shl 4) or digits[i * 2 + 1].hexValue()).toByte()
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------

    private fun frame(vararg payload: Int): ByteArray = frame(payload.toList())

    private fun frame(payload: List<Int>): ByteArray {
        val out = ByteArray(payload.size + 1)
        out[0] = payload.size.toByte()
        for (i in payload.indices) out[i + 1] = payload[i].toByte()
        return out
    }

    /** ASCII decimal digit for a small value (`infoType + '0'`, matching the DLL). */
    private fun asciiDigit(value: Int): Int = ('0'.code + value)

    /** Single uppercase ASCII-hex nibble (0-15). */
    private fun hexNibble(value: Int): Int = HEX[value and 0x0F].code

    /** Address as ASCII hex: one digit when < 16, otherwise two (high nibble first). */
    private fun addressBytes(address: Int): List<Int> =
        if (address < 0x10) listOf(hexNibble(address))
        else listOf(hexNibble(address ushr 4), hexNibble(address and 0x0F))

    private fun longestHexRun(s: String): String? {
        var best: String? = null
        var start = -1
        fun consider(end: Int) {
            if (start >= 0) {
                val run = s.substring(start, end)
                if (best == null || run.length > best!!.length) best = run
            }
            start = -1
        }
        for (i in s.indices) {
            if (s[i].isHexDigit()) { if (start < 0) start = i } else consider(i)
        }
        consider(s.length)
        return best
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun Char.hexValue(): Int = when (this) {
        in '0'..'9' -> this - '0'
        in 'a'..'f' -> this - 'a' + 10
        in 'A'..'F' -> this - 'A' + 10
        else -> 0
    }
}
