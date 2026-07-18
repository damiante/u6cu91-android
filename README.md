# u6cu91-android — community Android SDK for the U6-CU-91 UHF RFID reader

A from-scratch Android driver for the **U6-CU-91 (FONGWAH) UHF RFID reader/writer**,
reverse-engineered from the vendor's Windows SDK (`E7umf.dll`). The vendor ships no Android
SDK; this library fills that gap. **Validated against real hardware** (`0e6a:0317`, firmware
`V0224`) over USB-OTG.

No dependencies beyond `kotlinx-coroutines-core`. `minSdk 24`.

A sibling **Linux/Python SDK** for the same reader (`u6cu91-linux`) implements the identical
wire protocol and was the hardware bring-up tool; the two share test vectors but no code.

## API

Everything hangs off one class, `com.damiantesta.u6cu91.U6Cu91Reader`:

```kotlin
val reader = U6Cu91Reader(context)          // any Context; optional pollIntervalMs = 250

reader.connect()                             // Result<Unit>; raises Android's USB permission
                                             // dialog on first use
reader.connectionState                       // StateFlow<ConnectionState>

reader.inventory()                           // Flow<String> of uppercase EPC hex; polls while
                                             // collected, re-emits tags still in the field
reader.readTag(Bank.EPC, address = 0, words = 8)      // Result<ByteArray>
reader.writeTag(Bank.USER, address = 0, data = bytes) // Result<Unit>
reader.feedback(Feedback.BEEP, durationMs = 200)      // buzzer/LEDs; Result<Unit>

reader.disconnect()
```

Failures come back as `Result.failure`: `SecurityException` (USB permission denied),
`IllegalStateException` (no reader attached / interface can't be claimed), `U6Cu91Exception`
(disconnected mid-operation or the reader rejected a command).

The library's manifest contributes `<uses-feature android:name="android.hardware.usb.host"
android:required="false"/>` to consumers via manifest merge — no manifest changes needed in
the app, and `required="false"` keeps apps installable on devices without USB host support.

## Files

| File | Role |
|------|------|
| `U6Cu91Protocol.kt` | Pure frame/command codec. No Android deps → unit-tested (`U6Cu91ProtocolTest`) against the exact bytes read out of the DLL and real-hardware captures. |
| `U6Cu91UsbTransport.kt` | USB-host transport: device discovery, permission, interface claim, 64-byte report I/O. |
| `U6Cu91Reader.kt` | Public API; orchestrates connect/inventory/read/write/feedback. |

## How the protocol was recovered

The vendor DLL is `E7umf.dll` (PE32+, x86-64). Its imports told the transport story before
a single instruction was read:

- `HID.DLL!HidD_GetHidGuid` + `SETUPAPI!SetupDi*` → it enumerates **USB HID** devices.
- `KERNEL32!CreateFileA/ReadFile/WriteFile` on `\\.\COM%d` → a serial fallback we ignore on
  Android (the U6-**CU**-91 is the USB variant).

Disassembling the `uhf_*` exports (`objdump -d`) recovered the rest. Key routines:

- `uhf_connect(0x64, baud)` enumerates HID interfaces, **filters by device-path substring
  `vid_0e6a`** (VID `0x0E6A`; PID is *not* checked), opens each match and probes it with the
  identify command, keeping whichever answers.
- The identify/version handshake, `uhf_action`, `uhf_read`, `uhf_write`, `uhf_inventory`,
  `uhf_setAccessPassword`, `uhf_lockMemory`, `uhf_killTag` each build a command frame and call
  an internal link routine (`sub_1570`) that does the HID report I/O.

### Transport

Fixed **64-byte HID reports** in both directions (the DLL's `WriteFile`/`ReadFile` always use
length `0x40`). We send on the interrupt-OUT endpoint, falling back to a HID `SET_REPORT`
control transfer when the interface exposes no OUT endpoint.

### Frame format

```
request :  [LEN] [payload ...]            LEN = number of payload bytes
response:  [LEN] [0x02] [sub] [status] [data ...]   status 0x00 = success
```

The reader speaks **ASCII**: numeric parameters and tag data travel as ASCII-hex text (that is
what the DLL's `a_hex`/`hex_a` exports convert). A received report is self-framed as
`[chunkLen][data…]`; `chunkLen == 0x3F` (63) means the report was full and more follow, which
is how streamed inventory is reassembled.

### Command frames (verified against the disassembly, asserted in `U6Cu91ProtocolTest`)

| Operation | Bytes | DLL addr |
|-----------|-------|----------|
| Identify / version | `02 02 A1` | `sub_1330` |
| Buzzer + LED (`uhf_action`) | `04 02 91 <bits> <time10ms>` | `0x1d50` |
| Streaming inventory | `02 55 0D` (`'U' CR`) | `0x2020` |
| Paged inventory count | `03 02 55 80` | `0x21fa` |
| Paged inventory next | `03 02 55 91` | `0x2280` |
| Read (`uhf_read`) | `LL 02 41 52 <type> ',' <addr…> ',' <len>` (`"AR"`) | `0x1000` |
| Write (`uhf_write`) | `LL 02 41 57 <type> ',' <addr…> ',' <len> ',' <ascii-hex data>` (`"AW"`) | `0x1150` |
| Set access password | `0B 02 41 50 <8 bytes>` (`"AP"`) | `0x1ea0` |
| Lock memory | `0A 02 41 4C <3> ',' <3>` (`"AL"`) | `0x1f50` |
| Kill tag | `0D 02 41 4B <8 bytes> ',' '0'` (`"AK"`) | `0x1df0` |

`<type>` is `infoType + '0'` (EPC=1 → `'1'`). `<addr…>` is one uppercase hex digit when < 16,
otherwise two. `<len>` is a single hex digit (word count); each word is 4 ASCII-hex chars =
2 bytes. Inventory dispatch mirrors the DLL: identify first, and if the version string starts
with `'W'` use streaming inventory, otherwise the paged count+read variant.

## Hardware-confirmed details

The transport and inventory decode were validated against a physical **FONGWAH U6-CU-91**
(`0e6a:0317`, firmware `V0224`), first via the Linux bring-up SDK (`u6cu91-linux`, which
shares these exact frames and carries the raw captures as test vectors) and then end-to-end
from this library on an Android phone over USB-OTG. What that pinned down:

- **Two HID interfaces.** Interface 0 is keyboard emulation (input only); interface 1 is the
  vendor data channel (interrupt OUT + IN). `U6Cu91UsbTransport` claims the interface that has
  an interrupt OUT endpoint — claiming the keyboard makes every write time out.
- **Numbered reports.** The data interface's reports are numbered with `reportId == data
  length`, i.e. exactly the `[LEN][payload]` frame — `frame[0]` is the report id. So frames are
  written verbatim on the OUT endpoint (no padding, no extra prefix); reads return
  `[reportId=LEN][payload]`.
- **Paged inventory.** `V0224` reports a version starting `U`, so the SDK uses the paged path:
  `03 02 55 80` returns a tag count (`payload[3]`), then `03 02 55 91` per tag returns a record
  whose EPC is `payload[8]-4` bytes at `payload[11]` (`parsePagedTag`). This is pinned by unit
  tests built from the real captures.

Remaining protocol gaps are minor: multi-tag inventory is implemented per the vendor DLL but
was only exercised with a couple of tags at once, and the lock/kill/password command family is
decoded (table above) but not exposed in the API.

## Testing against the real reader

Unit tests (`./gradlew :u6cu91-android:testDebugUnitTest`) cover the protocol codec with no
hardware. The `UsbManager` transport needs the physical device:

1. Plug the reader into an Android phone/tablet through a USB-C (or micro-USB) **OTG** adapter.
   The reader draws 86–262 mA (bus-powered, within spec for OTG); if a phone is stingy about
   host power, use a powered OTG hub.
2. Install an app that uses this SDK and connect. Android shows an "Allow the app to access
   the USB device?" dialog on first connect (the transport requests permission at runtime) —
   accept it.
3. `adb logcat -s U6Cu91Reader:* U6Cu91Usb:*` should show `Opened … iface=1` then
   `identify -> 02 A1 00 …`, and decoded EPCs once a tag is presented.

Optional nicety for consuming apps: an intent filter + `device_filter.xml` (vendor id `0x0E6A`)
on your launch activity makes Android auto-launch the app and pre-grant permission on plug-in.

The standard AVD emulator does **not** cleanly forward host USB devices; use a physical device.

## Keeping in sync with the Linux SDK

`u6cu91-linux` (Python) is an independent port of the same wire protocol — no shared code,
only the spec. Both test suites embed the same real-hardware capture vectors (e.g. the paged
tag record starting `23 02 55 91 01 A0 …`). The Python SDK is the canonical reference for wire
behaviour (it was the bring-up tool); if frame building or parsing changes in one port, mirror
it in the other and keep the shared vectors identical.
