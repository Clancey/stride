package io.stride.spikes

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Build
import android.util.Log
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How a frame actually reaches the motor controller.
 *
 * Two transports, one interface, because `docs/DIRECT_MACHINE_PROTOCOL.md` establishes that both
 * carry the *same* frame / register / serializer path — a wired console over USB serial, a wireless
 * one over BLE GATT. Everything above this interface is transport-agnostic, so the probe that
 * confirms the framing and the command surface that uses it are written once.
 *
 * ## What this file is and is not
 *
 * This is the file the standing safety rule was really about. [FitProCodec] was safe because it had
 * no transport; this is the transport. Holding it to the same standard means being exact about what
 * it does and does not decide:
 *
 *  - It does **not** decide what to send. It takes bytes and returns bytes.
 *  - It does **not** know a speed from a fan state, and must not learn.
 *  - It does **not** gate anything. The gate is [FitProProbe.confirmed] and it is enforced in
 *    [DirectMachineCommands], where the caller's intent is still visible.
 *
 * A transport that also decided policy would put the safety rules in two files again, which is the
 * mistake [GlassOsCommands] and [MachineCoordinator] were split to avoid.
 *
 * Every call blocks. Callers must be off the main thread.
 */
interface FitProTransport : Closeable {

    /** Human-readable, for logs and the diagnostics screen. Never parsed. */
    val name: String

    /** True once the underlying device is open and usable. */
    val connected: Boolean

    /**
     * Which protocol generation the console on the other end speaks.
     *
     * Read from the hardware rather than assumed — see [FitProCodec.Variant]. The two generations
     * frame their requests differently enough that speaking the wrong one gets no reply at all.
     */
    val variant: FitProCodec.Variant

    /**
     * Send [frame] and wait for the machine's reply.
     *
     * Returns null on timeout or any transport failure, which callers must read as "we do not
     * know", never as "the machine refused" — the difference matters because a command whose reply
     * was lost may still have landed.
     */
    fun exchange(
        frame: ByteArray,
        command: FitProCodec.Command = FitProCodec.Command.READ_WRITE_DATA,
    ): ByteArray?

    /** True when this link is a radio, which the vendor gives an extra second on every command. */
    val isRadio: Boolean get() = false

    companion object {
        /**
         * Deadlines and read delays live on [FitProCodec.Command], because the vendor sets them per
         * command rather than per transport: a telemetry read and a serial-number query differ by
         * nearly two seconds. A single constant here is what this replaced, and it was 400 ms —
         * shorter than the vendor's read *delay* for most commands, so a console answering exactly
         * as designed was being written off as absent.
         */
        const val TAG_TIMEOUTS = "see FitProCodec.Command.timeoutMs"

        const val TAG = "FitProTransport"

        /**
         * Open whichever transport this console actually has, or null if neither is present.
         *
         * USB is tried first: a wired console is the documented primary, it needs no pairing, and
         * its failure mode (no matching device) is instant and unambiguous.
         */
        fun open(context: Context): FitProTransport? =
            UsbSerialTransport.open(context) ?: BleTransport.open(context)
    }
}

/**
 * Whether Android's synchronous `bulkTransfer` can actually move data over this endpoint.
 *
 * That is a wider question than "is this endpoint declared bulk": `bulkTransfer` is implemented on
 * top of usbfs's `USBDEVFS_BULK` ioctl, which the kernel honours for interrupt endpoints too — only
 * isochronous and control endpoints are out of reach of it. See [UsbSerialTransport.open] for why
 * that distinction is load-bearing here rather than academic.
 */
private val UsbEndpoint.isBulkOrInterrupt: Boolean
    get() = UsbSerialTransport.isDataPipeType(type)

/**
 * USB serial to a wired console — the `glassos_sindarin_usb` path.
 *
 * The vendor lock is not a convenience. `ICON = 8508` is the only vendor whose device this protocol
 * describes, and enumerating every attached USB device and talking to whichever one answered would
 * mean writing register frames into an unknown peripheral. Matching the vendor id is the difference
 * between addressing the treadmill and addressing something that happens to be plugged in.
 */
class UsbSerialTransport private constructor(
    private val manager: UsbManager,
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val readEndpoint: UsbEndpoint,
    private val writeEndpoint: UsbEndpoint,
    override val variant: FitProCodec.Variant,
) : FitProTransport {

    override val name: String
        get() = "USB ${device.deviceName} (${variant.name.lowercase()})"

    @Volatile private var closed = false

    /**
     * Which transfer mechanism this board answers on, or null until one has worked.
     *
     * Latched on the first success so the fallback in [transfer] is a one-time probe rather than a
     * doubled attempt on every frame. Only ever touched from `exchange`, which is `@Synchronized`.
     */
    private var useRequests: Boolean? = null

    /** How many more times [transfer] may try the mechanism it has not settled on. */
    private var probesLeft: Int = MECHANISM_PROBES

    /** Whether this link has ever had a transfer accepted. See [stalled]. */
    private var everMoved = false

    private var writePipe: Pipe? = null
    private var readPipe: Pipe? = null

    /**
     * Whether the link still exists, as opposed to "we have not closed it".
     *
     * Checked against the live device list, because a cable pulled mid-session leaves this object
     * perfectly intact. If this reported `!closed`, [MachineLink] would keep polling a dead handle
     * forever and never re-run the handshake against the reconnected console.
     */
    override val connected: Boolean
        get() = !closed && try {
            manager.deviceList.containsKey(device.deviceName)
        } catch (t: Throwable) {
            false
        }

    /**
     * One request, one reply, on the bulk endpoints.
     *
     * Synchronized because `bulkTransfer` on a shared connection is not reentrant and the protocol
     * is strictly request/response: two callers interleaving would each read the other's reply,
     * which on a register protocol means attributing one register's value to another.
     */
    @Synchronized
    override fun exchange(frame: ByteArray, command: FitProCodec.Command): ByteArray? {
        if (closed) return null
        val timeoutMs = command.timeoutMs(onBle = false)
        // The bare frame, on both generations. Neither chunks over USB, and the difference in how
        // they get there is worth stating because it is easy to conclude otherwise from a partial
        // read of iFit's code: FitPro1's *shared* `CommAdapter.SendBytes` does chunk — it walks
        // `commGroup.RequestBytes` — but `FitProUsbConsoleCommunicationAdapter` overrides it to send
        // `commGroup.OriginalBytes`, which is the unchunked frame. Only the BLE adapter inherits the
        // chunking base. FitPro2 does not chunk anywhere.
        return try {
            if (!write(frame)) return null
            // iFit pauses between writing and reading rather than reading straight away
            // (`SendBytesWithReadDelay`, `CommandBase.ReadDelayMs`). Cheap to honour and it stops
            // the first read racing a console that has not begun answering.
            val delay = command.readDelayMs
            if (delay > 0) Thread.sleep(delay)
            readAnswer(frame, timeoutMs)
        } catch (t: Throwable) {
            Log.w(FitProTransport.TAG, "usb exchange failed", t)
            null
        }
    }

    /**
     * Bring the console's framer into step before any command is sent.
     *
     * Transcribed from GlassOS's own USB open routine (`wh/c.X`), which is unambiguous about what it
     * is doing because it logs every step: *"Discarding buffer from console"*, *"Sending buffer full
     * of 0xFF"*, *"0xFF send successfully. Now reading"*, and then either *"Read the response but it
     * was not what was expected"* or *"Read the response and it was equal to the expected response.
     * Incrementing consecutiveBuffers"*. It requires **two consecutive** good buffers and gives up
     * after ten failures of either kind.
     *
     * The expected answer is the pattern itself: 64 bytes of `0xFF`, with index 3 a wildcard. That
     * is `ai.b.f824b` — the same constant the normal read path uses to *reject* a frame, because
     * during traffic an all-`0xFF` buffer is not data and during sync it is the whole point.
     *
     * ## Why this is the missing piece
     *
     * Without it the console answers, but out of step. Measured here: `DEVICE_INFO` sent as
     * `04 04 81 89` came back as `00 04 04 81` — a padding byte, then the request repeated rather
     * than answered. A frame like that passes every structural check, so it decodes as a plausible
     * refusal, and the console looks like it does not implement the protocol. It does. It had simply
     * never been synchronised.
     */
    private fun synchronise(): Boolean {
        val pattern = ByteArray(SYNC_BUFFER_BYTES) { 0xFF.toByte() }
        val buffer = ByteArray(SYNC_BUFFER_BYTES)
        var consecutive = 0
        var writeFailures = 0
        var readFailures = 0

        while (consecutive < SYNC_CONSECUTIVE && writeFailures < SYNC_MAX_FAILURES &&
            readFailures < SYNC_MAX_FAILURES
        ) {
            // Whatever the console was part-way through saying is not an answer to anything we are
            // about to ask. Deliberately unchecked: an empty pipe is the normal case here.
            transfer(readEndpoint, buffer, buffer.size, SYNC_TIMEOUT_MS)

            val sent = transfer(writeEndpoint, pattern.copyOf(), pattern.size, SYNC_TIMEOUT_MS)
            if (sent != pattern.size) {
                writeFailures++
                Thread.sleep(SYNC_RETRY_MS)
                continue
            }

            val read = transfer(readEndpoint, buffer, buffer.size, SYNC_TIMEOUT_MS)
            if (read != buffer.size || !isSyncPattern(buffer)) {
                readFailures++
                // Consecutive means consecutive. One bad buffer restarts the count, as it does in
                // GlassOS: the point is evidence that the pipe is quiet and aligned, not that it
                // managed it once.
                consecutive = 0
                Thread.sleep(SYNC_RETRY_MS)
                continue
            }
            consecutive++
        }
        return consecutive >= SYNC_CONSECUTIVE
    }

    /** The sync pattern: every byte `0xFF`, except index 3 which the console may write. */
    private fun isSyncPattern(buffer: ByteArray): Boolean =
        buffer.indices.all { it == 3 || buffer[it] == 0xFF.toByte() }

    /**
     * Read frames until one of them is an answer rather than an acknowledgement.
     *
     * Measured on a FitPro2 console: the first frame back from a `DEVICE_INFO` can be the request
     * *itself*, byte for byte. It is a well-formed frame with the right address, length, command and
     * checksum — so every structural check passes, and the byte a caller then reads as a status is
     * really the request's own checksum.
     *
     * GlassOS never trips over this because its USB path validates each read and simply reads again
     * when a frame is not usable (`rj/p`, `wh/c.r`). Reading exactly once and believing whatever came
     * back is the assumption that was wrong.
     */
    private fun readAnswer(request: ByteArray, timeoutMs: Long): ByteArray? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) return null
            val reply = readFrame(remainingMs) ?: return null
            if (!reply.contentEquals(request)) return reply
            Log.i(
                FitProTransport.TAG,
                "usb: console echoed the request; reading on for the answer",
            )
        }
    }

    /**
     * Put one buffer on the wire, whole.
     *
     * A short write is not a slow write. The console would be left holding a truncated frame, and
     * the next thing it received would be read as that frame's tail — which on a register protocol
     * is how a speed byte becomes a command byte.
     */
    private fun write(bytes: ByteArray): Boolean {
        val wrote = transfer(writeEndpoint, bytes, bytes.size, WRITE_TIMEOUT_MS)
        if (wrote != bytes.size) {
            Log.w(FitProTransport.TAG, "usb write incomplete ($wrote of ${bytes.size})")
            return false
        }
        return true
    }

    /**
     * Move one transfer's worth of bytes, by whichever mechanism this board actually answers on.
     *
     * ## Why there are two
     *
     * `bulkTransfer` is the proven path and stays the one tried first, including on the interrupt
     * endpoints this console enumerates (`vendor 8508 / product 3`, vendor class 255, an interrupt
     * pipe each way). It works there because it lands on usbfs's `USBDEVFS_BULK`, which the kernel
     * turns into an interrupt URB for an interrupt endpoint — verified on the hardware, not assumed.
     *
     * `UsbRequest` is the rescue path, and it is the one Android actually documents for interrupt
     * endpoints. It exists here because the bulk ioctl's support for interrupt endpoints is a kernel
     * behaviour rather than a platform guarantee, and a console where it is missing would otherwise
     * open, claim, and then refuse every byte with nothing to say why.
     *
     * Whichever succeeds first is remembered for the rest of the session, so this costs one extra
     * attempt on one transfer rather than a doubled attempt on every frame.
     *
     * Only a hard failure falls through. A short transfer moved bytes, and re-sending after one
     * would put part of a frame on the wire twice — which on a register protocol is how a speed byte
     * becomes a command byte.
     *
     * [probesLeft] bounds the search. A link that is failing for a reason neither mechanism can fix
     * — most often another app on the console holding the interface — must not spend the rest of its
     * life trying both every time.
     */
    private fun transfer(
        endpoint: UsbEndpoint,
        buffer: ByteArray,
        length: Int,
        timeoutMs: Int,
    ): Int {
        val settled = useRequests
        val first = attempt(settled ?: false, endpoint, buffer, length, timeoutMs)
        if (first >= 0) return moved(first, viaRequest = settled ?: false, settle = settled == null)
        if (settled != null || probesLeft <= 0) return stalled(first)
        probesLeft--

        val second = attempt(true, endpoint, buffer, length, timeoutMs)
        if (second < 0) return stalled(second)
        Log.i(FitProTransport.TAG, "usb: this board answers UsbRequest but not bulkTransfer")
        return moved(second, viaRequest = true, settle = true)
    }

    /** Record a transfer that the kernel accepted, and latch the mechanism that carried it. */
    private fun moved(result: Int, viaRequest: Boolean, settle: Boolean): Int {
        if (settle) useRequests = viaRequest
        everMoved = true
        blocked = false
        return result
    }

    /**
     * Record a transfer the kernel refused.
     *
     * Only a link that has **never** moved anything is reported as blocked. A -1 on its own means
     * very little: `readFrame` asks for bytes that may not be coming and a read that times out is a
     * normal, expected -1 on every idle poll. Reporting on that would tell a rider their console was
     * being held hostage by another app every time the treadmill had nothing to say.
     */
    private fun stalled(result: Int): Int {
        if (!everMoved) blocked = true
        return result
    }

    private fun attempt(
        viaRequest: Boolean,
        endpoint: UsbEndpoint,
        buffer: ByteArray,
        length: Int,
        timeoutMs: Int,
    ): Int = if (viaRequest) {
        queuedTransfer(endpoint, buffer, length, timeoutMs)
    } else {
        connection.bulkTransfer(endpoint, buffer, length, timeoutMs)
    }

    /**
     * One transfer through `UsbRequest`, the path Android documents for interrupt endpoints.
     *
     * ## Why this is more than queue-then-wait
     *
     * `requestWait` returns **whichever** request completed on this connection, not the one just
     * queued. This transport keeps one per endpoint, so a foreign or abandoned completion is a
     * routine possibility rather than a corner case, and taking it as our own is not a near miss:
     * the buffer of an uncompleted request holds nothing meaningful, so it reads as a successful
     * zero-byte transfer, which latches the mechanism and marks the link healthy. Our own request
     * meanwhile stays queued in the kernel — and AOSP *throws* rather than returning false when a
     * still-queued request is re-queued, so the next transfer raises `IllegalStateException` and
     * every one after it does the same. A link that reports itself healthy and can never move
     * another byte.
     *
     * So completions are matched by identity, foreign ones are recorded against whichever pipe owns
     * them and waited past, and a request the kernel never gives back is left marked outstanding
     * rather than re-queued.
     *
     * On a timeout the URB is still with the kernel, so it is cancelled and reaped. Skipping that is
     * how one command's reply gets attributed to the next.
     */
    private fun queuedTransfer(
        endpoint: UsbEndpoint,
        buffer: ByteArray,
        length: Int,
        timeoutMs: Int,
    ): Int {
        val pipe = pipeFor(endpoint) ?: return -1
        // Never re-queue a request the kernel still holds. One last attempt to get it back, then
        // refuse the transfer rather than throw.
        if (pipe.outstanding && !reap(pipe, CANCEL_REAP_MS)) {
            Log.w(FitProTransport.TAG, "usb: the request for ${endpoint.address} is still out")
            return -1
        }

        val payload = ByteBuffer.wrap(buffer, 0, length)
        val queued = try {
            pipe.request.queue(payload)
        } catch (t: Throwable) {
            Log.w(FitProTransport.TAG, "usb: could not queue a ${length}-byte request", t)
            return -1
        }
        if (!queued) {
            Log.w(FitProTransport.TAG, "usb: could not queue a ${length}-byte request")
            return -1
        }
        pipe.outstanding = true

        val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(1) * 1_000_000L
        while (true) {
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
            if (remainingMs <= 0) break
            val completed = awaitRequest(remainingMs) ?: break
            settle(completed)
            // Only our own completion says anything about our buffer.
            if (completed === pipe.request) return payload.position()
        }
        pipe.request.cancel()
        reap(pipe, CANCEL_REAP_MS)
        return -1
    }

    /** Whichever request the kernel hands back next, or null on a timeout or failure. */
    private fun awaitRequest(timeoutMs: Long): UsbRequest? = try {
        connection.requestWait(timeoutMs.coerceAtLeast(1))
    } catch (_: TimeoutException) {
        null
    } catch (t: Throwable) {
        Log.w(FitProTransport.TAG, "usb request wait failed", t)
        null
    }

    /** Record that the kernel has given [request] back, whichever pipe it belongs to. */
    private fun settle(request: UsbRequest) {
        listOfNotNull(writePipe, readPipe).forEach {
            if (it.request === request) it.outstanding = false
        }
    }

    /**
     * Try to get [pipe]'s request back, for up to [budgetMs]. True once it is reusable.
     *
     * Completions for the *other* pipe are collected on the way past rather than discarded, because
     * the alternative is leaving them to be mistaken for the next transfer's answer.
     */
    private fun reap(pipe: Pipe, budgetMs: Long): Boolean {
        val deadline = System.nanoTime() + budgetMs * 1_000_000L
        while (pipe.outstanding) {
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
            if (remainingMs <= 0) break
            val completed = awaitRequest(remainingMs) ?: break
            settle(completed)
        }
        return !pipe.outstanding
    }

    /** The long-lived request for one endpoint, made on first use. */
    private fun pipeFor(endpoint: UsbEndpoint): Pipe? {
        val cached = if (endpoint === writeEndpoint) writePipe else readPipe
        if (cached != null) return cached
        val created = UsbRequest()
        if (!created.initialize(connection, endpoint)) {
            Log.w(FitProTransport.TAG, "usb: could not initialise a request for ${endpoint.address}")
            created.close()
            return null
        }
        val pipe = Pipe(created)
        if (endpoint === writeEndpoint) writePipe = pipe else readPipe = pipe
        return pipe
    }

    /**
     * One endpoint's reusable request, and whether the kernel still has it.
     *
     * [outstanding] is the whole point. A `UsbRequest` may not be re-queued while it is out, and
     * asking whether it is out is not something the platform object will answer — so it is tracked
     * here, against every path that could return it.
     */
    private class Pipe(val request: UsbRequest) {
        var outstanding = false
    }

    /**
     * Read one whole frame, however many transfers that takes.
     *
     * A transfer returns whatever has arrived, not what was asked for, so a frame can be split
     * across reads. Byte 1 of a FitPro frame is its total length, which is what makes reassembly
     * possible at all — this reads the header, then keeps going until the declared length is
     * satisfied or the deadline passes.
     *
     * ## Resynchronising, and why it is not optional
     *
     * The stream does not always start where a frame does. Measured on a FitPro2 console
     * (`vendor 8508 / product 3`), a `DEVICE_INFO` sent to address 4 as `04 04 81 89` read back as
     * `00 04 04 81` — a leading `00` followed by the frame. Taken at face value that makes byte 1
     * the *address*, not the length, so the reader declares a 4-byte frame, truncates, and throws
     * the real answer away. Downstream that surfaced as a console answering `CMD_NOT_SUPPORTED` to
     * every command, which is a sentence about a machine that was never actually asked.
     *
     * A leading zero cannot begin a frame: byte 0 is the device address and address 0 is `NONE`.
     * GlassOS rejects exactly that (`ai/b.a`: *"Reason: 'bytes [0] == 0'"*) and reads again rather
     * than believing it — its USB path retries with a ramping timeout for this reason. So leading
     * zeroes are skipped here, and a header that still cannot be a frame is dropped a byte at a time
     * until one can, for as long as the deadline allows.
     */
    private fun readFrame(timeoutMs: Long): ByteArray? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        val chunk = ByteArray(readEndpoint.maxPacketSize.coerceAtLeast(64))
        var frame = ByteArray(0)
        var declared = -1

        while (true) {
            // Drop anything that cannot be the first byte of a frame. Cheap, and it is what turns a
            // stream with a padding byte in front of it back into a protocol.
            var dropped = 0
            while (frame.isNotEmpty() && frame[0] == 0.toByte()) {
                frame = frame.copyOfRange(1, frame.size)
                dropped++
            }
            if (dropped > 0) {
                declared = -1
                Log.i(FitProTransport.TAG, "usb: skipped $dropped leading zero byte(s)")
            }

            if (declared < 0 && frame.size >= 2) {
                val length = frame[1].toInt() and 0xFF
                if (length < FitProCodec.FRAME_OVERHEAD || length > FitProCodec.MAX_FRAME_LENGTH) {
                    // Not a length, so this was not a header. Give up on this byte rather than on
                    // the read: the frame may begin at the next one.
                    Log.i(FitProTransport.TAG, "usb: resyncing past a ${length}-byte length claim")
                    frame = frame.copyOfRange(1, frame.size)
                    continue
                }
                declared = length
            }
            if (declared in 1..frame.size) {
                // Trailing bytes would be the next frame, so hand up exactly this one and let the
                // caller's exact-fit check stay meaningful.
                return frame.copyOfRange(0, declared)
            }

            val remainingMs = (deadline - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) {
                if (frame.isNotEmpty()) {
                    Log.w(FitProTransport.TAG, "usb read timed out with ${frame.size} of $declared")
                }
                return null
            }
            val read = transfer(readEndpoint, chunk, chunk.size, remainingMs.toInt())
            if (read < 0) return null
            if (read == 0) continue
            frame += chunk.copyOfRange(0, read)
        }
    }

    @Synchronized
    /**
     * ## A limitation that is no longer only suspected
     *
     * `claimInterface(iface, force = true)` asks the kernel to detach whatever driver holds the
     * interface. Android implements that with `USBDEVFS_DISCONNECT`, and neither `releaseInterface`
     * nor `close` issues the matching `USBDEVFS_CONNECT` — so whatever was detached stays detached
     * after Stride lets go.
     *
     * This was recorded here as "very likely harmless, but unverified". It has since been verified,
     * and it is not harmless. On a Commercial 1750 with `com.ifit.glassos_service` running, taking
     * the interface left GlassOS reporting that the console had lost its connection to the treadmill,
     * and restarting the service did not bring it back; the console had to be rebooted. That is
     * exactly the "iFit stopped working after I tried direct access" report this note was written to
     * make recognisable, and it is now the expected outcome rather than a possibility.
     *
     * Two things follow, both done rather than left as notes. [open] claims plainly first and only
     * forces if that fails, so a board nothing else is driving is never detached at all. And the
     * rider's opt-in says outright that iFit may need a reboot afterwards, because a warning nobody
     * was given is the part of this that was actually broken.
     *
     * The only real repair for the forced case is a native `USBDEVFS_CONNECT` helper, which is a
     * much larger change than this file should grow on its own.
     */
    override fun close() {
        if (closed) return
        closed = true
        // Cancelled and reaped before they are freed, and freed before the connection they were
        // initialised against. A request outliving its connection is a native handle pointing at
        // released state; one freed while the kernel still holds its buffer is worse.
        listOfNotNull(writePipe, readPipe).forEach { pipe ->
            runCatching { pipe.request.cancel() }
            runCatching { reap(pipe, CANCEL_REAP_MS) }
            runCatching { pipe.request.close() }
        }
        writePipe = null
        readPipe = null
        try {
            connection.releaseInterface(iface)
            connection.close()
        } catch (t: Throwable) {
            Log.w(FitProTransport.TAG, "usb close failed", t)
        }
    }

    companion object {
        /** ICON Health & Fitness. The only vendor this protocol is documented against. */
        const val VENDOR_ICON = 8508

        /**
         * The same rule as [isBulkOrInterrupt], against a raw endpoint type.
         *
         * Separated so it can be tested: `UsbEndpoint` is final and cannot be constructed or mocked
         * in a unit test, and this predicate is the whole of the rule that decided whether an X22i
         * was reachable at all. A rule that important should not be the one thing with no test.
         */
        internal fun isDataPipeType(type: Int): Boolean =
            type == UsbConstants.USB_ENDPOINT_XFER_BULK ||
                type == UsbConstants.USB_ENDPOINT_XFER_INT

        /**
         * Set when the last open failed at [android.hardware.usb.UsbDeviceConnection.claimInterface].
         *
         * Reported rather than only logged, because a rider cannot read logcat and this failure
         * looks exactly like every other one from the outside: device present, permission granted,
         * pipes correct, still no connection.
         */
        @Volatile private var claimFailed = false

        /**
         * Set when a link that opened cleanly could not move a single byte.
         *
         * A third distinct failure, and the one seen on this hardware: the device is present, the
         * permission is granted, the pipes are right, `claimInterface` returns true — and every
         * transfer comes straight back having moved nothing. What that means in practice is that
         * something else on the console owns the interface. iFit's own `glassos_service` claims it
         * at boot and holds it, and `claimInterface(force = true)` only detaches *kernel* drivers;
         * there is no way to take a usbfs claim off another app, and Stride must not try.
         *
         * Reported rather than only logged, because from the rider's side this is indistinguishable
         * from every other "no direct connection" — and unlike the others it has an obvious cure
         * (stop using iFit's console app, or reboot with direct access already selected) that nobody
         * would guess from the generic sentence.
         */
        @Volatile internal var blocked = false

        /**
         * Set when the interface was held and Stride declined to take it by force.
         *
         * The one refusal in this file that is a *choice* rather than a failure, so it says so.
         * Reported ahead of every other case because it is the only one where the rider's treadmill
         * is still working and the honest answer is "direct access cannot have this console" rather
         * than "something went wrong".
         */
        @Volatile internal var heldByGlassOs = false

        /**
         * How many transfers may try both mechanisms before the search is given up.
         *
         * Small: whichever one works, works on the first frame. This is only here so a link that is
         * failing for a reason neither can fix does not double its own traffic forever.
         */
        private const val MECHANISM_PROBES = 3

        /** The read and write pipes of the interface [open] would choose, or null if there is none. */
        internal fun dataInterface(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
            for (index in 0 until device.interfaceCount) {
                val candidate = device.getInterface(index)
                val usable = (0 until candidate.endpointCount)
                    .map { candidate.getEndpoint(it) }
                    .filter { it.isBulkOrInterrupt }
                val read = usable.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
                val write = usable.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
                if (read != null && write != null) return Triple(candidate, read, write)
            }
            return null
        }

        /**
         * A short description of the pipes [open] would actually use, or null when there are none.
         *
         * Deliberately built from [dataInterface] rather than from every endpoint on the device.
         * Listing pipes that `open` would never select is how a diagnostic ends up describing a
         * console as perfectly fine while the code refuses it — which is the exact failure mode this
         * whole line of reporting exists to prevent.
         */
        private fun usableEndpoints(device: UsbDevice): String? {
            val (_, read, write) = dataInterface(device) ?: return null
            return listOf(read, write).joinToString(", ") { endpoint ->
                val kind = if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                    "interrupt"
                } else {
                    "bulk"
                }
                val way = if (endpoint.direction == UsbConstants.USB_DIR_IN) "in" else "out"
                "$kind $way"
            }
        }

        /** One request message, padded. See [FitProCodec.chunkMessages]. */
        private const val MESSAGE_SIZE = 20

        /**
         * How long one bulk write may take.
         *
         * Separate from the reply deadline, and much shorter, because a chunked request is several
         * writes and giving each of them the whole reply budget would let a single stalled transfer
         * consume the time meant for the answer.
         */
        private const val WRITE_TIMEOUT_MS = 500

        /**
         * How long to wait for a cancelled request to come back before giving up on reaping it.
         *
         * Short because the URB is already cancelled and the kernel completes it promptly; the wait
         * exists so the *next* transfer's `requestWait` cannot be handed this one's completion, not
         * to find out anything about it.
         */
        private const val CANCEL_REAP_MS = 50L

        /**
         * The sync exchange GlassOS performs before it will speak to a USB console.
         *
         * All four numbers come from `wh/c.X`: a 64-byte pattern, two consecutive good buffers to
         * call it synchronised, ten failures of either kind before giving up, and a 500 ms pause
         * between attempts. The per-transfer budget is the adapter's own `f17339x`, 300 ms.
         */
        private const val SYNC_BUFFER_BYTES = 64
        private const val SYNC_CONSECUTIVE = 2
        private const val SYNC_MAX_FAILURES = 10
        private const val SYNC_TIMEOUT_MS = 300
        private const val SYNC_RETRY_MS = 500L


        /**
         * The console this device is, or null when it is not one we can speak to.
         *
         * Matching the vendor alone was enough while there was one generation to talk to. It is not
         * now: the product id is how a console says which protocol it speaks (2 = FitPro1,
         * 3 = FitPro2), and the two are framed differently enough that guessing wrong is silence.
         */
        internal fun variantOf(device: UsbDevice): FitProCodec.Variant? =
            if (device.vendorId != VENDOR_ICON) null
            else FitProCodec.Variant.fromUsbProductId(device.productId)

        /** The attached console, whichever generation, or null. */
        internal fun consoleDevice(manager: UsbManager): UsbDevice? = try {
            manager.deviceList.values.firstOrNull { variantOf(it) != null }
        } catch (t: Throwable) {
            Log.w(FitProTransport.TAG, "could not enumerate USB devices", t)
            null
        }

        /**
         * Whether Android has actually granted access to the attached console.
         *
         * Asked of the USB service rather than inferred from a broadcast: below API 33 the
         * permission-result action can be sent by any app on the device, so the extra it carries is
         * a claim and this is the fact.
         */
        internal fun hasConsolePermission(context: Context): Boolean {
            val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
            val device = consoleDevice(manager) ?: return false
            return try {
                manager.hasPermission(device)
            } catch (t: Throwable) {
                false
            }
        }

        /**
         * What the USB bus actually shows, in a sentence a rider can read back to us.
         *
         * The reason this exists rather than a log line: "Stride checked the USB port and found
         * nothing to talk to" is true for at least three different situations — no device at all, a
         * device whose ids we do not recognise, and a device we recognise but have not been granted
         * access to — and the fix is different for each. A report that cannot tell them apart costs
         * a round trip with whoever owns the treadmill, and there is only one of those.
         *
         * Unrecognised devices are listed with their ids precisely because those are the numbers
         * that would let us add support for a console nobody here has ever seen.
         */
        internal fun describeBus(context: Context): String {
            val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
                ?: return "Android reported no USB service on this console."
            val devices = try {
                manager.deviceList.values.toList()
            } catch (t: Throwable) {
                return "Stride could not read the USB device list (${t.javaClass.simpleName})."
            }
            if (devices.isEmpty()) return "Nothing is attached to the USB port."

            val console = devices.firstOrNull { variantOf(it) != null }
            if (console != null) {
                val variant = variantOf(console)
                val granted = try {
                    manager.hasPermission(console)
                } catch (t: Throwable) {
                    false
                }
                // The pipes are named because their *type* is what made this console unreachable,
                // and no amount of "a console is on USB" could have shown that. A description that
                // stops before the endpoints cannot tell a board Stride can drive from one it is
                // about to reject.
                val pipes = usableEndpoints(console)
                val suffix = when {
                    pipes == null ->
                        " Stride found no interface on it with a usable pipe each way, so it " +
                            "cannot be opened."
                    heldByGlassOs ->
                        " Data pipes: $pipes, but iFit's own service is running and holds the " +
                            "connection to the treadmill. Stride will not take it: doing that cuts " +
                            "iFit off from the belt until the machine is power-cycled at the wall, " +
                            "and this console doesn't answer the direct protocol anyway. Stay on " +
                            "iFit (GlassOS) — it is the connection that works on this machine."
                    claimFailed ->
                        " Data pipes: $pipes, but Android would not let Stride claim the " +
                            "interface — something else on the console may still hold it."
                    blocked ->
                        " Data pipes: $pipes, and Stride opened it but could not move a single " +
                            "byte. Another app on the console is holding the treadmill's USB " +
                            "connection — iFit's own console software does this from boot, and " +
                            "Android gives no way to take it back. Rebooting with direct access " +
                            "already selected is what usually clears it."
                    else -> " Data pipes: $pipes."
                }
                return if (granted) {
                    "An iFit ${variant?.name} console is on USB and Stride may use it.$suffix"
                } else {
                    "An iFit ${variant?.name} console is on USB, but Android hasn't granted Stride " +
                        "access to it yet.$suffix"
                }
            }
            val listed = devices.joinToString(", ") { "vendor ${it.vendorId}/product ${it.productId}" }
            return "USB has $listed, none of which is a console Stride recognises."
        }

        /**
         * The broadcast Android sends once the rider answers the USB permission dialog.
         *
         * Public because something has to *listen* for it. Nothing did: the dialog was raised, the
         * rider tapped Allow, and the result went nowhere — the grant only took effect on whatever
         * retry happened to come next, which on a console showing "found nothing to talk to" is not
         * a connection anybody waits around for.
         */
        const val ACTION_PERMISSION = "io.stride.spikes.USB_PERMISSION"

        /**
         * Find and open the console's serial device, or null.
         *
         * Returns null rather than requesting permission and waiting: a transport that blocked on a
         * system dialog would freeze whichever thread opened it, and the direct path is always
         * entered from a rider action that can simply be repeated once the grant exists.
         * [requestPermission] is the deliberate, separate step.
         */
        fun open(context: Context): UsbSerialTransport? {
            val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
            val device = consoleDevice(manager) ?: return null
            val variant = variantOf(device) ?: return null
            if (!manager.hasPermission(device)) {
                Log.i(FitProTransport.TAG, "no USB permission for ${device.deviceName}")
                return null
            }
            // Endpoints 0 and 1 of the first interface, per the documented bulkTransfer path. The
            // direction flags are read rather than assumed, because an interface that enumerated
            // them the other way round would otherwise have us writing into the read endpoint.
            //
            // Bulk *or* interrupt, not bulk alone. A GlassOS-era console (product id 3) enumerates
            // real bulk endpoints, which is what this originally matched — but a FitPro1 console
            // (product id 2, e.g. an X22i) reports itself as `class=3` ("ICON Generic HID") with two
            // *interrupt* endpoints (`dumpsys usb` on real hardware: type=3, i.e.
            // `USB_ENDPOINT_XFER_INT`, on both). Filtering on bulk alone means `open()` never finds
            // an interface on that console and returns null before ever attempting a handshake —
            // silently, since every other failure branch here logs and this one did not, which is
            // why the direct path read as "no console" instead of "wrong endpoint type". `bulkTransfer`
            // still works against the interrupt endpoints once found: it maps to usbfs's
            // `USBDEVFS_BULK` ioctl, which the kernel accepts for both bulk and interrupt endpoints —
            // this is the same trick vendor-HID "generic report pipe" USB devices are commonly driven
            // with elsewhere, not something specific to this console.
            // One usable pipe each way, from [dataInterface] — the same function the diagnostics
            // describe, so what the settings screen reports is by construction what `open` would
            // choose rather than a second implementation that can drift away from it.
            val selected = dataInterface(device)
            if (selected == null) {
                Log.w(
                    FitProTransport.TAG,
                    "usb: ${device.deviceName} has no interface with a usable pipe each way",
                )
                return null
            }
            val (iface, read, write) = selected

            val connection = manager.openDevice(device) ?: return null
            // Plainly first, and forcibly only when there is nothing to break.
            //
            // `force = true` asks Android to issue `USBDEVFS_DISCONNECT`, which detaches whatever
            // holds the interface — and neither `releaseInterface` nor `close` ever issues the
            // matching `USBDEVFS_CONNECT`, so the detach outlives Stride's session. On this hardware
            // that is not a theoretical risk: taking the interface from a running `glassos_service`
            // left the console reporting DISCONNECTED from its own belt, and it stayed that way
            // through a service restart and an Android reboot.
            //
            // So when the plain claim fails *and the GlassOS daemon is answering*, this refuses
            // rather than forces. Two reasons, and either alone would be enough. The daemon holding
            // the interface is the working path to the belt, and cutting it leaves the rider with a
            // treadmill nothing can drive until it is power-cycled at the wall. And there is nothing
            // to win: a GlassOS-era console answers this codec's `DEVICE_INFO` with
            // `CMD_NOT_SUPPORTED` — see `Variant.FITPRO2` — so the interface would be taken, the
            // machine broken, and the handshake would fail anyway.
            //
            // Forcing is kept for the case direct access actually exists for: a console with no
            // GlassOS daemon at all, where whatever holds the interface is a kernel driver and there
            // is no working path to destroy.
            val claimed = connection.claimInterface(iface, false) || run {
                if (TransportDetector.glassOsListening()) {
                    Log.w(
                        FitProTransport.TAG,
                        "usb: ${device.deviceName} is held by another process and GlassOS is " +
                            "running; refusing to force the claim",
                    )
                    heldByGlassOs = true
                    return@run false
                }
                connection.claimInterface(iface, true)
            }
            if (!claimed) {
                // Worth its own line. Detaching whatever holds the interface is best-effort rather
                // than guaranteed on an OEM kernel. Without this the failure was indistinguishable
                // from every other null return, so a console that was present, permitted and had
                // exactly the right pipes would still report "no direct connection" with nothing to
                // say which of the two had happened.
                if (!heldByGlassOs) {
                    Log.w(
                        FitProTransport.TAG,
                        "usb: could not claim interface on ${device.deviceName}; " +
                            "another driver may still hold it",
                    )
                    claimFailed = true
                }
                connection.close()
                return null
            }
            heldByGlassOs = false
            claimFailed = false
            // A fresh link has not failed to move anything yet. Left set, a previous session's
            // failure would be reported against a console that is working.
            blocked = false
            Log.i(FitProTransport.TAG, "usb open: ${device.deviceName} as $variant")
            val transport =
                UsbSerialTransport(manager, device, connection, iface, read, write, variant)
            // Before any command is sent. Reported rather than fatal: a console that will not sync
            // is unlikely to answer afterwards, but "it never synchronised" is a far more useful
            // report than a handshake that silently finds nothing, and the diagnostics can only
            // describe a transport that exists.
            if (transport.synchronise()) {
                Log.i(FitProTransport.TAG, "usb: console synchronised")
            } else {
                Log.w(FitProTransport.TAG, "usb: console did not answer the 0xFF sync")
            }
            return transport
        }

        /**
         * Ask the platform for permission to talk to the console's USB device.
         *
         * Separate from [open] so the permission dialog is raised by an explicit rider action and
         * never as a side effect of polling.
         */
        /**
         * True while a dialog raised by [requestPermission] has not been answered.
         *
         * One guard shared by both callers. The settings screen asks as part of the rider's tap, and
         * the link's own retry asks when it finds an ungranted device — and those happen within a
         * second of each other, because tapping the row is what triggers the retry. Android does not
         * deduplicate, so without this the rider is handed the same dialog twice and answering the
         * first leaves a second one sitting on a console with no Back button.
         */
        private val permissionInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

        /** Called when the dialog has been answered, so the next genuine need can ask again. */
        internal fun permissionSettled() {
            permissionInFlight.set(false)
        }

        fun requestPermission(context: Context): Boolean {
            val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
            val device = consoleDevice(manager) ?: return false
            if (manager.hasPermission(device)) {
                permissionSettled()
                return true
            }
            if (!permissionInFlight.compareAndSet(false, true)) {
                Log.i(FitProTransport.TAG, "usb permission dialog already open; not asking twice")
                return false
            }
            // MUTABLE, and this is the one place it has to be.
            //
            // The old comment here said the intent "carries no extras we would ever want a recipient
            // to fill in", and that is exactly backwards: the recipient is Android, and the extra it
            // fills in is `EXTRA_PERMISSION_GRANTED` — the answer to the dialog. An immutable
            // PendingIntent cannot be written to, so the broadcast comes back reporting *denied* no
            // matter what the rider tapped. Nothing noticed while nothing was listening for the
            // broadcast; the moment something does, immutable makes every grant look like a refusal.
            //
            // FLAG_MUTABLE only exists from API 31. Below that a PendingIntent is mutable by
            // default and the flag is unnecessary, which is why this is 0 rather than IMMUTABLE.
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val intent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_PERMISSION).setPackage(context.packageName),
                flags,
            )
            manager.requestPermission(device, intent)
            return false
        }
    }
}

/**
 * BLE GATT to a wireless console.
 *
 * The characteristic roles are taken from GlassOS's own setup (`hc/o`, read from smali because JADX
 * loses the assignment) and they are the opposite of what the names suggest. They are named from the
 * *device's* point of view:
 *
 *  - **DeviceRx** (`1534`) is what the device receives on, so it is where this host **writes**.
 *  - **DeviceTx** (`1535`) is what the device transmits on, so it is what this host **notifies** on.
 *
 * Both live inside the FitPro service (`1533`). GlassOS looks up DeviceRx first, keeps it as the
 * write target, then calls `setCharacteristicNotification` on DeviceTx and writes the standard
 * `2902` descriptor — the error string "Failed to find deviceTx characteristic" sits immediately
 * before that call and pins which is which.
 *
 * Everything on this link is asynchronous and must be waited for. A GATT write returns "accepted for
 * transmission", not "delivered", and issuing the next packet before `onCharacteristicWrite` arrives
 * is how a multi-packet frame gets silently truncated or reordered.
 *
 * Bonded devices only, and matched by service UUID. Scanning for and connecting to an arbitrary
 * advertiser that happened to expose a `1533` service would be the BLE equivalent of writing
 * register frames into an unknown USB peripheral.
 */
@SuppressLint("MissingPermission")
class BleTransport private constructor(
    private val gatt: BluetoothGatt,
    private val write: BluetoothGattCharacteristic,
) : FitProTransport {

    /**
     * BLE cannot say which generation it is.
     *
     * A USB console declares itself in its product id; a BLE peripheral exposes the same FitPro
     * service either way and offers nothing to tell them apart. [FitProCodec.Variant.FITPRO1] is
     * named because its BLE behaviour is the half that is verified — `FitProBleConsoleCommunication`
     * inherits the chunking `SendBytes`, and the `[0x02,0x04,0x02,len]` envelope survives because
     * the `Format` setter only strips it for non-BLE links. Whether a product-3 board over BLE is
     * framed identically is **not** established by anything read so far, and is written down as
     * unknown rather than assumed.
     */
    override val variant: FitProCodec.Variant get() = FitProCodec.Variant.FITPRO1

    override val name: String get() = "BLE ${gatt.device?.address ?: "?"}"

    @Volatile private var closed = false

    /**
     * Set by the GATT callback when the link actually drops, as distinct from us closing it.
     *
     * Without this, [connected] would be "we have not called close()", which stays true forever
     * after the console is switched off — and [MachineLink] would never re-run the handshake.
     */
    @Volatile private var dropped = false

    override val connected: Boolean get() = !closed && !dropped

    /**
     * Completed replies, reassembled from notification fragments.
     *
     * Depth 1: this is a strict request/response protocol and a second reply arriving before the
     * first is consumed means something is already wrong, so it is dropped rather than allowed to
     * satisfy the *next* request with a stale value.
     */
    private val replies = ArrayBlockingQueue<ByteArray>(1)

    /** Signals completion of one GATT characteristic write. */
    private val writeAcks = ArrayBlockingQueue<Boolean>(1)

    private val assembler = FitProCodec.MessageReassembler { frame -> replies.offer(frame) }

    override val isRadio: Boolean get() = true

    @Synchronized
    override fun exchange(frame: ByteArray, command: FitProCodec.Command): ByteArray? {
        if (!connected) return null
        val timeoutMs = command.timeoutMs(onBle = true)
        replies.clear()
        assembler.reset()
        return try {
            // The FitPro2 envelope goes on before chunking: the chunk header describes the length of
            // what is being carried, and what the console expects to be carried is the enveloped
            // frame, not the bare one (`th/q`, then `th/o`).
            val payload = FitProCodec.fitPro2Envelope(frame)
            for (packet in FitProCodec.chunkMessages(payload)) {
                if (!writeAndWait(packet, timeoutMs)) return null
            }
            replies.poll(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            Log.w(FitProTransport.TAG, "ble exchange failed", t)
            null
        }
    }

    /**
     * Send one packet and wait for the stack to confirm it went out.
     *
     * Returns false on rejection, on timeout, or on a failed completion status. Any of those means
     * the frame is now incomplete, and a partially transmitted register frame must never be followed
     * by a read of whatever the console makes of it.
     */
    private fun writeAndWait(packet: ByteArray, timeoutMs: Long): Boolean {
        writeAcks.clear()
        if (!submit(packet)) return false
        return writeAcks.poll(timeoutMs, TimeUnit.MILLISECONDS) == true
    }

    /**
     * Hand one packet to the stack, using the API appropriate to the runtime.
     *
     * The pre-33 path is deprecated rather than absent, and the console runs Android 8/9, so the
     * deprecated call is the one that actually executes there. Both are kept because the build
     * compiles against a modern SDK and lint treats an unguarded new API as an error — an
     * API-33-only overload would compile happily and throw `NoSuchMethodError` on the hardware.
     */
    @Suppress("DEPRECATION")
    private fun submit(packet: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // BluetoothStatusCodes, not BluetoothGatt: the API 33 overload returns a different
            // constant family. Both spell success as 0, so the wrong one would have worked by
            // coincidence until a failure code diverged.
            gatt.writeCharacteristic(
                write,
                packet,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            write.value = packet
            write.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt.writeCharacteristic(write)
        }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            gatt.disconnect()
            gatt.close()
        } catch (t: Throwable) {
            Log.w(FitProTransport.TAG, "ble close failed", t)
        }
    }

    companion object {
        /** The FitPro GATT service both characteristics live in (`kc/k`, "FitPro"). */
        val SERVICE: UUID = UUID.fromString("00001533-1412-efde-1523-785feabcd123")

        /** Named from the device's side: the device transmits here, so this host notifies on it. */
        val DEVICE_TX: UUID = UUID.fromString("00001535-1412-efde-1523-785feabcd123")

        /** Named from the device's side: the device receives here, so this host writes to it. */
        val DEVICE_RX: UUID = UUID.fromString("00001534-1412-efde-1523-785feabcd123")

        /** The standard client-characteristic-configuration descriptor. */
        val CCC: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** How long to wait for connect + discovery + notification arming before giving up. */
        private const val SETUP_TIMEOUT_MS = 8_000L

        /**
         * Connect to a bonded console and arm notifications, or null.
         *
         * Blocking, and deliberately so: everything above this interface is written as
         * request/response on a background thread, and an asynchronous open would push connection
         * state into every caller.
         */
        fun open(context: Context): BleTransport? {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                ?: return null
            val adapter: BluetoothAdapter = manager.adapter ?: return null
            if (!adapter.isEnabled) return null

            val bonded = try {
                adapter.bondedDevices ?: emptySet()
            } catch (t: SecurityException) {
                Log.w(FitProTransport.TAG, "no bluetooth permission", t)
                return null
            }
            if (bonded.isEmpty()) return null

            for (device in bonded) {
                val transport = connect(context, device)
                if (transport != null) return transport
            }
            return null
        }

        private fun connect(context: Context, device: BluetoothDevice): BleTransport? {
            val discovered = CountDownLatch(1)
            val armed = CountDownLatch(1)
            var writeTarget: BluetoothGattCharacteristic? = null
            var pending: BleTransport? = null
            // Per-attempt, not shared: a previous device having armed notifications says nothing
            // about this one, and a stale `true` would let a mute link be treated as ready.
            val notificationsArmed = AtomicBoolean(false)

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        g.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        // Tell an already-built transport that its link is gone, so the layer above
                        // re-runs the handshake instead of polling a dead GATT forever.
                        pending?.dropped = true
                        discovered.countDown()
                        armed.countDown()
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        discovered.countDown()
                        armed.countDown()
                        return
                    }
                    // Scoped to the FitPro service rather than scanning every service for a matching
                    // characteristic id, so an unrelated peripheral cannot present a look-alike.
                    val service = g.getService(SERVICE)
                    val target = service?.getCharacteristic(DEVICE_RX)
                    val notify = service?.getCharacteristic(DEVICE_TX)
                    if (target == null || notify == null) {
                        // Not the console. Every bonded device gets looked at, and one that does
                        // not expose both characteristics is simply not this machine.
                        discovered.countDown()
                        armed.countDown()
                        return
                    }
                    writeTarget = target
                    discovered.countDown()

                    if (!g.setCharacteristicNotification(notify, true)) {
                        armed.countDown()
                        return
                    }
                    val descriptor = notify.getDescriptor(CCC)
                    if (descriptor == null) {
                        armed.countDown()
                        return
                    }
                    if (!armNotifications(g, descriptor)) armed.countDown()
                }

                override fun onDescriptorWrite(
                    g: BluetoothGatt,
                    descriptor: BluetoothGattDescriptor,
                    status: Int,
                ) {
                    if (descriptor.uuid != CCC) return
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.w(FitProTransport.TAG, "ble notifications refused: $status")
                    } else {
                        notificationsArmed.set(true)
                    }
                    armed.countDown()
                }

                override fun onCharacteristicWrite(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    if (characteristic.uuid != DEVICE_RX) return
                    pending?.writeAcks?.offer(status == BluetoothGatt.GATT_SUCCESS)
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                ) {
                    if (characteristic.uuid != DEVICE_TX) return
                    val value = characteristic.value ?: return
                    pending?.assembler?.accept(value)
                }

                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    if (characteristic.uuid != DEVICE_TX) return
                    pending?.assembler?.accept(value)
                }
            }

            val gatt = try {
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            } catch (t: SecurityException) {
                Log.w(FitProTransport.TAG, "connectGatt denied", t)
                return null
            } ?: return null

            val ready = await(discovered) && await(armed)
            val characteristic = writeTarget
            // Notifications are the return path. Without them this link can transmit and never hear
            // an answer, which would present as a machine that refuses everything.
            if (!ready || characteristic == null || !notificationsArmed.get()) {
                gatt.close()
                return null
            }
            Log.i(FitProTransport.TAG, "ble open: ${device.address}")
            return BleTransport(gatt, characteristic).also { pending = it }
        }

        private fun await(latch: CountDownLatch): Boolean = try {
            latch.await(SETUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        /**
         * Write the CCC descriptor so the machine's answers actually arrive.
         *
         * Split out for the same reason the characteristic write is: the value-carrying overload is
         * API 33+, and the console is Android 8/9.
         */
        @Suppress("DEPRECATION")
        private fun armNotifications(g: BluetoothGatt, descriptor: BluetoothGattDescriptor): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(descriptor)
            }
    }
}
