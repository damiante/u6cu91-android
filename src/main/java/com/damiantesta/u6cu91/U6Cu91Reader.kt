package com.damiantesta.u6cu91

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Connection lifecycle of a [U6Cu91Reader]. */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}

/** Tag memory banks; [infoType] matches the vendor SDK's read/write infoType parameter. */
enum class Bank(internal val infoType: Int) {
    EPC(1),
    TID(2),
    USER(3),
    RESERVED(4),
}

/** Matches the vendor `uhf_action` capabilities: buzzer and the three status LEDs. */
enum class Feedback {
    BEEP,
    RED_LED,
    GREEN_LED,
    YELLOW_LED,
}

/** Failure reported by the reader or raised when it is not connected. */
class U6Cu91Exception(message: String) : Exception(message)

/**
 * Driver for the U6-CU-91 UHF RFID reader/writer over Android USB host, speaking the
 * reverse-engineered USB-HID protocol (see [U6Cu91Protocol] and this module's README).
 *
 * Usage: construct with any [Context] (the application context is retained), call [connect]
 * while the device is attached — Android's USB permission dialog is raised if needed — then
 * collect [inventory] for EPCs and use [readTag]/[writeTag]/[feedback] as required.
 *
 * Failed operations return `Result.failure`: [SecurityException] when USB permission is
 * denied, [IllegalStateException] when no reader is attached or it cannot be claimed, and
 * [U6Cu91Exception] when the reader is disconnected mid-operation or rejects a command.
 *
 * A single [Mutex] serialises USB access so inventory polling, feedback, and read/write never
 * interleave reports on the shared endpoints. All I/O runs on [Dispatchers.IO].
 */
class U6Cu91Reader(
    context: Context,
    private val pollIntervalMs: Long = 250,
) {

    private val transport = U6Cu91UsbTransport(context)
    private val io = Mutex()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** Set from the identify handshake; the DLL uses a "W"-prefixed version to select streaming. */
    @Volatile
    private var streamingInventory = true

    suspend fun connect(): Result<Unit> {
        _connectionState.value = ConnectionState.CONNECTING
        val opened = withContext(Dispatchers.IO) { transport.open() }
        opened.onFailure {
            _connectionState.value = ConnectionState.DISCONNECTED
            Log.w(TAG, "connect failed: ${it.message}")
            return Result.failure(it)
        }

        withContext(Dispatchers.IO) { io.withLock { handshake() } }
        _connectionState.value = ConnectionState.CONNECTED
        return Result.success(Unit)
    }

    suspend fun disconnect() {
        withContext(Dispatchers.IO) { io.withLock { transport.close() } }
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Continuously polls for tags in the field while collected, emitting each sighting as an
     * uppercase EPC hex string. The same tag is re-emitted on every poll cycle it remains in
     * range; consumers are responsible for de-duplication.
     */
    fun inventory(): Flow<String> = flow {
        while (true) {
            if (transport.isOpen) {
                val epcs = runCatching { io.withLock { pollInventory() } }
                    .onFailure { Log.w(TAG, "inventory poll failed: ${it.message}") }
                    .getOrDefault(emptyList())
                for (epc in epcs) emit(epc)
            }
            delay(pollIntervalMs)
        }
    }.flowOn(Dispatchers.IO)

    /** Reads [words] 16-bit words from [bank] starting at word address [address]. */
    suspend fun readTag(bank: Bank, address: Int, words: Int): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            io.withLock {
                val response = exchange(U6Cu91Protocol.read(bank.infoType, address, words))
                    ?: return@withLock Result.failure<ByteArray>(notConnected())
                val hex = U6Cu91Protocol.readData(response)
                    ?: return@withLock Result.failure<ByteArray>(
                        U6Cu91Exception("Read rejected by reader")
                    )
                Result.success(U6Cu91Protocol.asciiHexToBytes(hex))
            }
        }

    /** Writes [data] (whole 16-bit words) to [bank] starting at word address [address]. */
    suspend fun writeTag(bank: Bank, address: Int, data: ByteArray): Result<Unit> =
        withContext(Dispatchers.IO) {
            io.withLock {
                val words = (data.size + 1) / 2 // 1 word == 2 bytes == 4 ASCII-hex chars
                val frame = U6Cu91Protocol.write(
                    bank.infoType, address, words, U6Cu91Protocol.bytesToAsciiHex(data),
                )
                val response = exchange(frame) ?: return@withLock Result.failure<Unit>(notConnected())
                if (U6Cu91Protocol.isAck(response)) Result.success(Unit)
                else Result.failure(U6Cu91Exception("Write rejected by reader"))
            }
        }

    /** Fires the buzzer or an LED for [durationMs] (reader resolution is 10ms, max 2550ms). */
    suspend fun feedback(feedback: Feedback, durationMs: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            io.withLock {
                val bits = when (feedback) {
                    Feedback.BEEP -> U6Cu91Protocol.ACTION_BEEP
                    Feedback.RED_LED -> U6Cu91Protocol.ACTION_RED_LED
                    Feedback.GREEN_LED -> U6Cu91Protocol.ACTION_GREEN_LED
                    Feedback.YELLOW_LED -> U6Cu91Protocol.ACTION_YELLOW_LED
                }
                val units = (durationMs / 10).coerceIn(1, 255) // uhf_action time is in 10ms units
                val response = exchange(U6Cu91Protocol.action(bits, units))
                if (response != null && U6Cu91Protocol.isAck(response)) Result.success(Unit)
                else Result.failure(notConnected())
            }
        }

    // -- internals (all called while holding [io]) ------------------------------------------------

    private fun handshake() {
        transport.drain() // clear any stale report left buffered from a prior session
        val response = exchange(U6Cu91Protocol.identify())
        val versionByte = response?.payload?.getOrNull(3)?.toInt()?.and(0xFF)
        streamingInventory = versionByte == null || versionByte == 'W'.code
        Log.i(
            TAG,
            "identify -> ${response?.payload?.toHex() ?: "no response"}; " +
                "streamingInventory=$streamingInventory",
        )
    }

    private fun pollInventory(): List<String> =
        if (streamingInventory) pollStreamingInventory() else pollPagedInventory()

    private fun pollStreamingInventory(): List<String> {
        if (!transport.write(U6Cu91Protocol.inventoryStream())) return emptyList()
        val body = readStream()
        if (body.isEmpty()) return emptyList()
        Log.d(TAG, "inventory body: ${body.toHex()}")
        return U6Cu91Protocol.parseInventory(body)
    }

    /** Paged variant (U6-CU-91 V0224): ask for a count, then pull that many single-tag records. */
    private fun pollPagedInventory(): List<String> {
        val count = U6Cu91Protocol.parsePagedCount(exchange(U6Cu91Protocol.inventoryCount()))
        val epcs = ArrayList<String>(count)
        repeat(count) {
            U6Cu91Protocol.parsePagedTag(exchange(U6Cu91Protocol.inventoryNext()))
                ?.let { epcs += it }
        }
        return epcs
    }

    /** Writes a frame and reads back one response frame. */
    private fun exchange(frame: ByteArray): U6Cu91Protocol.Response? {
        if (!transport.write(frame)) return null
        val report = transport.read() ?: return null
        return U6Cu91Protocol.parse(report)
    }

    /**
     * Reassembles a streamed response. Each report is `[chunkLen][data...]`; a chunk length of
     * 63 (0x3F) means the report was full and more follow, so we keep reading until a short
     * chunk, an empty chunk, or a read timeout.
     */
    private fun readStream(): ByteArray {
        val out = ByteArrayOutputStream()
        repeat(MAX_STREAM_REPORTS) {
            val report = transport.read() ?: return out.toByteArray()
            val len = report[0].toInt() and 0xFF
            if (len == 0) return out.toByteArray()
            out.write(report, 1, minOf(len, U6Cu91Protocol.REPORT_SIZE - 1))
            if (len < U6Cu91Protocol.REPORT_SIZE - 1) return out.toByteArray()
        }
        return out.toByteArray()
    }

    private fun notConnected() = U6Cu91Exception("U6-CU-91 is not connected")

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private companion object {
        const val TAG = "U6Cu91Reader"
        const val MAX_STREAM_REPORTS = 64
    }
}
