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
import android.os.Build
import android.util.Log
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
            readFrame(timeoutMs)
        } catch (t: Throwable) {
            Log.w(FitProTransport.TAG, "usb exchange failed", t)
            null
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
        val wrote = connection.bulkTransfer(writeEndpoint, bytes, bytes.size, WRITE_TIMEOUT_MS)
        if (wrote != bytes.size) {
            Log.w(FitProTransport.TAG, "usb write incomplete ($wrote of ${bytes.size})")
            return false
        }
        return true
    }

    /**
     * Read one whole frame, however many transfers that takes.
     *
     * `bulkTransfer` returns whatever has arrived, not what was asked for, so a frame can be split
     * across reads. Byte 1 of a FitPro frame is its total length, which is what makes reassembly
     * possible at all — this reads the header, then keeps going until the declared length is
     * satisfied or the deadline passes. Returning the first partial read instead, which is what this
     * replaced, hands the parser a header with a missing tail.
     */
    private fun readFrame(timeoutMs: Long): ByteArray? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        val chunk = ByteArray(readEndpoint.maxPacketSize.coerceAtLeast(64))
        var frame = ByteArray(0)
        var declared = -1

        while (true) {
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) {
                if (frame.isNotEmpty()) {
                    Log.w(FitProTransport.TAG, "usb read timed out with ${frame.size} of $declared")
                }
                return null
            }
            val read = connection.bulkTransfer(readEndpoint, chunk, chunk.size, remainingMs.toInt())
            if (read < 0) return null
            if (read == 0) continue
            frame += chunk.copyOfRange(0, read)

            if (declared < 0 && frame.size >= 2) {
                declared = frame[1].toInt() and 0xFF
                // A frame shorter than its own header is corruption, and waiting for more bytes
                // would just stall until the deadline behind a stream that is already unusable.
                if (declared < FitProCodec.FRAME_OVERHEAD) return null
            }
            if (declared in 1..frame.size) {
                // Trailing bytes would be the next frame, so hand up exactly this one and let the
                // caller's exact-fit check stay meaningful.
                return frame.copyOfRange(0, declared)
            }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
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

        private const val ACTION_PERMISSION = "io.stride.spikes.USB_PERMISSION"

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
            val iface = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull { candidate ->
                    (0 until candidate.endpointCount)
                        .map { candidate.getEndpoint(it) }
                        .count { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK } >= 2
                } ?: return null

            val endpoints = (0 until iface.endpointCount).map { iface.getEndpoint(it) }
            val read = endpoints.firstOrNull {
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN
            } ?: return null
            val write = endpoints.firstOrNull {
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT
            } ?: return null

            val connection = manager.openDevice(device) ?: return null
            if (!connection.claimInterface(iface, true)) {
                connection.close()
                return null
            }
            Log.i(FitProTransport.TAG, "usb open: ${device.deviceName} as $variant")
            return UsbSerialTransport(manager, device, connection, iface, read, write, variant)
        }

        /**
         * Ask the platform for permission to talk to the console's USB device.
         *
         * Separate from [open] so the permission dialog is raised by an explicit rider action and
         * never as a side effect of polling.
         */
        fun requestPermission(context: Context): Boolean {
            val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
            val device = consoleDevice(manager) ?: return false
            if (manager.hasPermission(device)) return true
            // FLAG_IMMUTABLE is required from API 31 and harmless below it; the intent carries no
            // extras we would ever want a recipient to fill in.
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
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
