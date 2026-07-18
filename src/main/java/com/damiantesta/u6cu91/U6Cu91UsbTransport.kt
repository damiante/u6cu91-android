package com.damiantesta.u6cu91

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * USB-host transport for the U6-CU-91, over Android's [UsbManager]. Confirmed against a
 * FONGWAH U6-CU-91 (`0e6a:0317`) via the Linux bring-up SDK (`u6cu91-linux/`):
 *
 * - The reader exposes **two HID interfaces**: interface 0 is keyboard emulation (input only)
 *   and interface 1 is the vendor data channel (interrupt OUT + IN, 64-byte). We must claim
 *   the one that has an interrupt OUT endpoint — claiming the keyboard makes writes time out.
 * - The data interface uses **numbered HID reports whose report id equals the data length**,
 *   which is exactly the protocol's `[LEN][payload]` frame (`frame[0]` is the report id). So a
 *   frame is written verbatim on the OUT endpoint — no padding, no extra report-id byte — and
 *   reads come back as `[reportId=LEN][payload]`.
 *
 * All I/O here is blocking; callers move it off the main thread.
 */
internal class U6Cu91UsbTransport(context: Context) {

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    /** Per-app broadcast action, so two apps embedding this SDK never collide. */
    private val actionUsbPermission = "${appContext.packageName}.U6CU91_USB_PERMISSION"

    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null

    val isOpen: Boolean get() = connection != null

    /** First connected device matching the reader's vendor id, or null if none is attached. */
    fun findReader(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { it.vendorId == U6Cu91Protocol.VENDOR_ID }

    /**
     * Locates the reader, obtains USB permission (prompting the user if needed), claims its HID
     * interface, and resolves its endpoints. Returns a descriptive failure when no matching
     * device is attached (e.g. on an emulator) or permission is denied.
     */
    suspend fun open(): Result<Unit> {
        val device = findReader()
            ?: return Result.failure(
                IllegalStateException(
                    "No U6-CU-91 found (looking for USB vendor id " +
                        "0x%04X)".format(U6Cu91Protocol.VENDOR_ID)
                )
            )
        logTopology(device)

        if (!usbManager.hasPermission(device)) {
            Log.i(TAG, "requesting USB permission for ${device.deviceName}")
            if (!requestPermission(device)) {
                return Result.failure(SecurityException("USB permission denied for ${device.deviceName}"))
            }
        }
        Log.i(TAG, "permission granted=${usbManager.hasPermission(device)}")

        // The data interface is the HID one carrying an interrupt OUT endpoint (interface 1);
        // interface 0 is keyboard emulation (input only) and must not be claimed.
        val data = selectDataInterface(device)
            ?: return Result.failure(
                IllegalStateException("Reader has no HID data interface with interrupt IN+OUT")
            )
        Log.i(TAG, "selected data iface=${data.iface.id} inEp=0x%02X outEp=0x%02X"
            .format(data.inEp.address, data.outEp.address))

        val conn = usbManager.openDevice(device)
            ?: return Result.failure(IllegalStateException("Could not open ${device.deviceName}"))

        if (!conn.claimInterface(data.iface, true)) {
            Log.w(TAG, "claimInterface(force=true) failed for iface=${data.iface.id}")
            conn.close()
            return Result.failure(IllegalStateException("Could not claim HID data interface"))
        }

        connection = conn
        usbInterface = data.iface
        endpointIn = data.inEp
        endpointOut = data.outEp
        Log.i(TAG, "Opened ${device.deviceName} vid=0x%04X pid=0x%04X iface=${data.iface.id}"
            .format(device.vendorId, device.productId))
        return Result.success(Unit)
    }

    /** Dumps how Android enumerates this device (interfaces + endpoints) for bring-up. */
    private fun logTopology(device: UsbDevice) {
        Log.i(TAG, "device ${device.deviceName} vid=0x%04X pid=0x%04X interfaces=${device.interfaceCount}"
            .format(device.vendorId, device.productId))
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val eps = (0 until iface.endpointCount).joinToString(", ") { e ->
                val ep = iface.getEndpoint(e)
                val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                val type = when (ep.type) {
                    UsbConstants.USB_ENDPOINT_XFER_INT -> "INT"
                    UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                    UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CTRL"
                    UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOC"
                    else -> "?"
                }
                "0x%02X/%s/%s".format(ep.address, dir, type)
            }
            Log.i(TAG, "  iface[$i] id=${iface.id} class=${iface.interfaceClass} " +
                "sub=${iface.interfaceSubclass} proto=${iface.interfaceProtocol} eps=[$eps]")
        }
    }

    private class DataInterface(
        val iface: UsbInterface,
        val inEp: UsbEndpoint,
        val outEp: UsbEndpoint,
    )

    /** First HID interface exposing both an interrupt IN and interrupt OUT endpoint. */
    private fun selectDataInterface(device: UsbDevice): DataInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass != UsbConstants.USB_CLASS_HID) continue
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_INT) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
            }
            if (inEp != null && outEp != null) return DataInterface(iface, inEp, outEp)
        }
        return null
    }

    fun close() {
        val conn = connection
        val iface = usbInterface
        if (conn != null && iface != null) {
            runCatching { conn.releaseInterface(iface) }
            runCatching { conn.close() }
        }
        connection = null
        usbInterface = null
        endpointIn = null
        endpointOut = null
    }

    /** Sends [frame] verbatim; `frame[0]` (the LEN byte) is the numbered-report id. */
    fun write(frame: ByteArray): Boolean {
        val conn = connection ?: return false
        val out = endpointOut
        val sent = if (out != null) {
            conn.bulkTransfer(out, frame, frame.size, IO_TIMEOUT_MS)
        } else {
            // Fallback (unused on this reader, which has an OUT endpoint): HID SET_REPORT, with
            // the report id in wValue and the remaining payload as the control-transfer data.
            val reportId = frame[0].toInt() and 0xFF
            val body = frame.copyOfRange(1, frame.size)
            conn.controlTransfer(
                REQ_TYPE_SET_REPORT, REQ_SET_REPORT, OUTPUT_REPORT_TYPE or reportId,
                usbInterface?.id ?: 0, body, body.size, IO_TIMEOUT_MS,
            )
        }
        return sent >= 0
    }

    /** Reads one report `[reportId=LEN][payload...]`, trimmed to its actual length, or null. */
    fun read(timeoutMs: Int = IO_TIMEOUT_MS): ByteArray? {
        val conn = connection ?: return null
        val ep = endpointIn ?: return null
        val buf = ByteArray(U6Cu91Protocol.REPORT_SIZE)
        val n = conn.bulkTransfer(ep, buf, buf.size, timeoutMs)
        return if (n < 0) null else buf.copyOf(n)
    }

    /** Discards any buffered input reports (e.g. a stale response from a prior session). */
    fun drain(maxReports: Int = 16) {
        repeat(maxReports) { if (read(DRAIN_TIMEOUT_MS) == null) return }
    }

    private suspend fun requestPermission(device: UsbDevice): Boolean =
        suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != actionUsbPermission) return
                    runCatching { appContext.unregisterReceiver(this) }
                    // EXTRA_PERMISSION_GRANTED only arrives on a mutable PendingIntent; fall back
                    // to querying the manager, which is the authoritative post-decision state.
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) ||
                        usbManager.hasPermission(device)
                    Log.i(TAG, "permission result granted=$granted")
                    if (cont.isActive) cont.resume(granted)
                }
            }
            val filter = IntentFilter(actionUsbPermission)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(receiver, filter)
            }
            cont.invokeOnCancellation { runCatching { appContext.unregisterReceiver(receiver) } }

            // Must be MUTABLE: the system fills EXTRA_PERMISSION_GRANTED into this broadcast when
            // it sends the result, and an immutable PendingIntent would drop that extra. The
            // intent is package-explicit (setPackage), which keeps the mutable PendingIntent safe.
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val intent = PendingIntent.getBroadcast(
                appContext, 0, Intent(actionUsbPermission).setPackage(appContext.packageName), flags,
            )
            usbManager.requestPermission(device, intent)
        }

    private companion object {
        const val TAG = "U6Cu91Usb"
        const val IO_TIMEOUT_MS = 1000
        const val DRAIN_TIMEOUT_MS = 20

        // HID class control-transfer constants for the SET_REPORT fallback.
        const val REQ_TYPE_SET_REPORT = 0x21   // host->device | class | interface
        const val REQ_SET_REPORT = 0x09
        const val OUTPUT_REPORT_TYPE = 0x0200  // report type 2 (output) in the high byte of wValue
    }
}
