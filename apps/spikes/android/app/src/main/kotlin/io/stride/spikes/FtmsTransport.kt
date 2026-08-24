package io.stride.spikes

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What [FtmsMachineCommands] and [FtmsClient] need from a fitness machine.
 *
 * An interface rather than the concrete transport for the same reason [FitProTransport] is one: the
 * interesting logic above it — mapping a machine's reply onto a [MachineAck] the coordinator will
 * trust, and deciding whether a pushed sample is still a reading — is exactly the logic that must be
 * testable without a Bluetooth radio in the room. [FtmsTransport] is the only production
 * implementation.
 */
interface FtmsLink {

    /** Human-readable, for logs and the diagnostics screen. Never parsed. */
    val name: String

    /** True while the GATT link is up. False once it drops or is closed. */
    val connected: Boolean

    /** What the machine says it can do, or null if it did not answer. */
    val features: FtmsCodec.Features?

    val speedRange: FtmsCodec.SpeedRange?

    val inclinationRange: FtmsCodec.InclinationRange?

    /** The most recent telemetry sample and the monotonic time it arrived, or null if none has. */
    fun latest(): Pair<FtmsCodec.TreadmillData, Long>?

    /** The last workout state the machine volunteered, as a GlassOS `WORKOUT_*` value. */
    fun announcedWorkoutState(): Int?

    /**
     * Write one Control Point command and wait for the machine's indication.
     *
     * Returns null on timeout or transport failure, which callers **must** read as "we do not know",
     * never as "the machine refused" — a command whose reply was lost may still have landed.
     */
    fun command(frame: ByteArray, timeoutMs: Long = FtmsTransport.CONTROL_TIMEOUT_MS):
        FtmsCodec.ControlResponse?
}

/**
 * A BLE GATT link to one Fitness Machine Service peripheral.
 *
 * ## How this differs in shape from [FitProTransport]
 *
 * FitPro is request/response: ask for a register, get a value. FTMS is **push**. The machine
 * notifies Treadmill Data on its own schedule and nothing asks it to, so this transport is a
 * *cache with a timestamp* rather than an exchange. Only the Control Point is request/response, and
 * it is the only thing here that can move a belt.
 *
 * That difference is why [latest] hands back a timestamp alongside the sample. `MachineLink` polls
 * every couple of seconds and must be able to tell "the machine is reporting 0 kph" from "the
 * machine stopped talking to us ten seconds ago" — and a cache with no clock cannot. A number that
 * has quietly stopped updating is more dangerous than no number, because it looks exactly like a
 * number that is still true.
 *
 * ## Why bonded devices are preferred over scanning
 *
 * Scanning for BLE peripherals below API 31 requires a **location** permission, because the OS
 * treats nearby-radio enumeration as a location signal. Stride is a launcher, and asking a rider for
 * location so they can see their treadmill's speed is a bad trade that also lands badly in a
 * permission review. So:
 *
 * - **Bonded devices are always tried**, and need no scan and no location.
 * - **Scanning happens only on API 31+**, where `BLUETOOTH_SCAN` with `neverForLocation` is
 *   sufficient and already declared in the manifest.
 *
 * On the older consoles this project targets, that means the rider pairs the machine once in Android
 * settings. That is a real limitation, written down rather than papered over.
 *
 * ## On the lint suppression
 *
 * Every Bluetooth call in this class is already guarded, which is what the lint check is asking for
 * — it just cannot see it. Scanning is gated on an explicit [canScan] permission check, and every
 * other call sits inside a `try`/`catch` that handles `SecurityException` by degrading to "no
 * machine" rather than crashing a launcher. `BleTransport` carries the same annotation for the same
 * reason. The suppression is a statement that the guards exist, not permission to omit them: a new
 * unguarded call added below would be a real bug that this annotation would hide, so guard it.
 */
@SuppressLint("MissingPermission")
class FtmsTransport private constructor(
    private val gatt: BluetoothGatt,
    private val controlPoint: BluetoothGattCharacteristic,
) : FtmsLink, Closeable {

    /** Human-readable, for logs and the diagnostics screen. Never parsed. */
    override val name: String get() = "FTMS ${gatt.device?.address ?: "?"}"

    @Volatile private var closed = false

    /**
     * Set by the GATT callback when the link actually drops, as distinct from us closing it.
     *
     * Without this, [connected] would mean "we have not called close()", which stays true forever
     * after the machine is switched off — and `MachineLink` would never re-run the handshake.
     */
    @Volatile private var dropped = false

    override val connected: Boolean get() = !closed && !dropped

    /** What the machine says it can do. Read once at connect; null when it did not answer. */
    @Volatile override var features: FtmsCodec.Features? = null
        private set

    @Volatile override var speedRange: FtmsCodec.SpeedRange? = null
        private set

    @Volatile override var inclinationRange: FtmsCodec.InclinationRange? = null
        private set

    @Volatile private var sample: FtmsCodec.TreadmillData? = null
    @Volatile private var sampleAt: Long = 0L

    /**
     * The last workout state the machine *volunteered*, as a GlassOS `WORKOUT_*` value.
     *
     * FTMS has no "what state are you in" read — the machine only announces transitions on the
     * Status characteristic. So this is a running belief updated by notification, and null until the
     * machine says something. Null is honest: it means nobody has told us.
     */
    @Volatile private var announcedWorkoutState: Int? = null

    /** Completed Control Point indications. Depth 1: this is strictly one command at a time. */
    private val responses = ArrayBlockingQueue<ByteArray>(1)

    /** Signals completion of one Control Point write. */
    private val writeAcks = ArrayBlockingQueue<Boolean>(1)

    /**
     * The most recent telemetry sample and the monotonic time it arrived, or null if none has.
     *
     * Callers decide what "too old" means; the transport does not, because the freshness rule is a
     * safety decision and belongs with the rest of them.
     */
    override fun latest(): Pair<FtmsCodec.TreadmillData, Long>? =
        sample?.let { it to sampleAt }

    override fun announcedWorkoutState(): Int? = announcedWorkoutState

    /**
     * Write one Control Point command and wait for the machine's indication.
     *
     * Returns null on timeout or transport failure, which callers **must** read as "we do not know",
     * never as "the machine refused" — a command whose reply was lost may still have landed. That is
     * the whole reason [MachineAck.NoAnswer] is distinct from [MachineAck.Refused].
     */
    @Synchronized
    override fun command(frame: ByteArray, timeoutMs: Long): FtmsCodec.ControlResponse? {
        if (!connected) return null
        responses.clear()
        return try {
            if (!writeAndWait(frame, timeoutMs)) return null
            val reply = responses.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return null
            val parsed = FtmsCodec.parseControlResponse(reply)
            // A reply to some *other* op code means the machine is answering a command we are no
            // longer waiting on. Treating it as this command's answer would let a stale "success"
            // confirm a setpoint that was never acknowledged.
            val sent = (frame.firstOrNull()?.toInt() ?: -1) and 0xFF
            if (parsed != null && parsed.requestOpCode != sent) {
                Log.w(TAG, "control reply for op ${parsed.requestOpCode}, expected $sent")
                return null
            }
            parsed
        } catch (t: Throwable) {
            Log.w(TAG, "ftms command failed", t)
            null
        }
    }

    private fun writeAndWait(frame: ByteArray, timeoutMs: Long): Boolean {
        writeAcks.clear()
        if (!submit(frame)) return false
        return writeAcks.poll(timeoutMs, TimeUnit.MILLISECONDS) == true
    }

    /**
     * Hand one frame to the stack, using the API appropriate to the runtime.
     *
     * The pre-33 path is deprecated rather than absent, and the consoles this targets run Android
     * 8/9, so the deprecated call is the one that actually executes there. Both are kept because the
     * build compiles against a modern SDK: an API-33-only overload would compile happily and throw
     * `NoSuchMethodError` on the hardware.
     */
    @Suppress("DEPRECATION")
    private fun submit(frame: ByteArray): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                controlPoint,
                frame,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            controlPoint.value = frame
            controlPoint.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt.writeCharacteristic(controlPoint)
        }
    } catch (t: SecurityException) {
        Log.w(TAG, "no bluetooth permission for write", t)
        false
    }

    override fun close() {
        closed = true
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }

    companion object {
        const val TAG = "FtmsTransport"

        /**
         * How long to wait for a Control Point indication.
         *
         * Longer than FitPro's 400 ms because this is a radio link to a separate machine rather than
         * a wire to a microcontroller, and short enough that a mute machine degrades to "no answer"
         * instead of freezing an overlay on a console with no physical Back button.
         */
        const val CONTROL_TIMEOUT_MS = 2_000L

        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val SETUP_STEP_TIMEOUT_MS = 3_000L
        private const val SCAN_TIMEOUT_MS = 6_000L

        /** Expand a SIG 16-bit assigned number into the full 128-bit UUID. */
        internal fun uuid(assigned: Int): UUID =
            UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", assigned))

        private val SERVICE = uuid(FtmsCodec.Uuid.SERVICE)
        private val TREADMILL_DATA = uuid(FtmsCodec.Uuid.TREADMILL_DATA)
        private val CONTROL_POINT = uuid(FtmsCodec.Uuid.CONTROL_POINT)
        private val STATUS = uuid(FtmsCodec.Uuid.STATUS)
        private val FEATURE = uuid(FtmsCodec.Uuid.FEATURE)
        private val SPEED_RANGE = uuid(FtmsCodec.Uuid.SPEED_RANGE)
        private val INCLINATION_RANGE = uuid(FtmsCodec.Uuid.INCLINATION_RANGE)
        private val CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * Find and open an FTMS machine, or null if there is not one we can reach.
         *
         * Blocking, and deliberately so: everything above this is written as request/response on a
         * background thread, and an asynchronous open would push connection state into every caller.
         */
        fun open(context: Context): FtmsTransport? {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                ?: return null
            val adapter: BluetoothAdapter = manager.adapter ?: return null
            if (!adapter.isEnabled) return null

            for (device in candidates(context, adapter)) {
                val transport = connect(context, device)
                if (transport != null) return transport
            }
            return null
        }

        /**
         * Devices worth trying, bonded first.
         *
         * Bonded devices cost nothing to enumerate and need no location permission. Scanning is
         * additive and only attempted where it is permission-free; see the class note.
         */
        private fun candidates(
            context: Context,
            adapter: BluetoothAdapter,
        ): List<BluetoothDevice> {
            val bonded = try {
                adapter.bondedDevices?.toList() ?: emptyList()
            } catch (t: SecurityException) {
                Log.w(TAG, "no bluetooth permission to list bonded devices", t)
                emptyList()
            }
            val scanned = if (canScan(context)) scanForService(adapter) else emptyList()
            // Bonded first, then anything advertising the service that is not already in the list.
            val seen = bonded.mapTo(mutableSetOf()) { it.address }
            return bonded + scanned.filter { seen.add(it.address) }
        }

        /**
         * Whether a scan is permitted without asking for location.
         *
         * Below API 31 the answer is always no — not because the scan would fail, but because it
         * would require a location grant this app deliberately does not hold.
         */
        private fun canScan(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        }

        private fun scanForService(adapter: BluetoothAdapter): List<BluetoothDevice> {
            val scanner = adapter.bluetoothLeScanner ?: return emptyList()
            val found = LinkedHashMap<String, BluetoothDevice>()
            val done = CountDownLatch(1)
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device = result.device ?: return
                    found[device.address] = device
                    // One match is enough to stop early: an FTMS machine advertising the service is
                    // what we came for, and continuing to scan only spends the rider's time.
                    done.countDown()
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.w(TAG, "ftms scan failed: $errorCode")
                    done.countDown()
                }
            }
            // Filtered in the controller rather than in our callback, so an environment full of BLE
            // peripherals does not turn into a wakeup per advertisement.
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            return try {
                scanner.startScan(listOf(filter), settings, callback)
                done.await(SCAN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                found.values.toList()
            } catch (t: Throwable) {
                Log.w(TAG, "ftms scan could not start", t)
                emptyList()
            } finally {
                runCatching { scanner.stopScan(callback) }
            }
        }

        private fun connect(context: Context, device: BluetoothDevice): FtmsTransport? {
            val discovered = CountDownLatch(1)
            // Every GATT operation completes through one of these. Depth 1 because the Android GATT
            // stack permits exactly one outstanding operation, so a second completion arriving
            // before the first is consumed means something is already wrong.
            val descriptorWrites = ArrayBlockingQueue<Boolean>(1)
            val reads = ArrayBlockingQueue<Pair<UUID, ByteArray?>>(1)
            var service: android.bluetooth.BluetoothGattService? = null
            var pending: FtmsTransport? = null

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        try {
                            g.discoverServices()
                        } catch (t: SecurityException) {
                            Log.w(TAG, "no permission to discover services", t)
                            discovered.countDown()
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        pending?.dropped = true
                        discovered.countDown()
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        // Scoped to the FTMS service rather than hunting every service for a
                        // matching characteristic id, so an unrelated peripheral cannot present a
                        // look-alike characteristic and be driven as if it were a treadmill.
                        service = g.getService(SERVICE)
                    }
                    discovered.countDown()
                }

                override fun onDescriptorWrite(
                    g: BluetoothGatt,
                    descriptor: BluetoothGattDescriptor,
                    status: Int,
                ) {
                    descriptorWrites.offer(status == BluetoothGatt.GATT_SUCCESS)
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicRead(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
                    val value = if (status == BluetoothGatt.GATT_SUCCESS) characteristic.value else null
                    reads.offer(characteristic.uuid to value)
                }

                override fun onCharacteristicRead(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                    status: Int,
                ) {
                    reads.offer(characteristic.uuid to value.takeIf { status == BluetoothGatt.GATT_SUCCESS })
                }

                override fun onCharacteristicWrite(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    if (characteristic.uuid != CONTROL_POINT) return
                    pending?.writeAcks?.offer(status == BluetoothGatt.GATT_SUCCESS)
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
                    pending?.onNotification(characteristic.uuid, characteristic.value)
                }

                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    pending?.onNotification(characteristic.uuid, value)
                }
            }

            val gatt = try {
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            } catch (t: SecurityException) {
                Log.w(TAG, "connectGatt denied", t)
                null
            } ?: return null

            if (!discovered.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                runCatching { gatt.close() }
                return null
            }

            val ftms = service
            val control = ftms?.getCharacteristic(CONTROL_POINT)
            val data = ftms?.getCharacteristic(TREADMILL_DATA)
            if (ftms == null || control == null || data == null) {
                // Not a treadmill-shaped FTMS machine. Every candidate gets looked at, and one that
                // does not expose both is simply not what we are here for.
                runCatching { gatt.close() }
                return null
            }

            val transport = FtmsTransport(gatt, control)
            pending = transport

            // Notifications before reads: a machine that starts pushing telemetry the moment it is
            // subscribed should not have those first samples land before there is anywhere to put
            // them. `transport` already exists by this point, so there is.
            val armed = arm(gatt, data, descriptorWrites, indicate = false) &&
                arm(gatt, control, descriptorWrites, indicate = true)
            if (!armed) {
                Log.w(TAG, "could not arm FTMS notifications")
                transport.close()
                return null
            }
            // Status is genuinely optional — it is how the machine announces a rider pressing its own
            // stop button, which is valuable but not required to read a speed.
            ftms.getCharacteristic(STATUS)?.let { arm(gatt, it, descriptorWrites, indicate = false) }

            transport.features = FtmsCodec.parseFeatures(
                read(gatt, ftms.getCharacteristic(FEATURE), reads),
            )
            transport.speedRange = FtmsCodec.parseSpeedRange(
                read(gatt, ftms.getCharacteristic(SPEED_RANGE), reads),
            )
            transport.inclinationRange = FtmsCodec.parseInclinationRange(
                read(gatt, ftms.getCharacteristic(INCLINATION_RANGE), reads),
            )

            return transport
        }

        /** Subscribe to one characteristic, notify or indicate, and wait for the stack to confirm. */
        @Suppress("DEPRECATION")
        private fun arm(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            acks: ArrayBlockingQueue<Boolean>,
            indicate: Boolean,
        ): Boolean = try {
            acks.clear()
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                false
            } else {
                val descriptor = characteristic.getDescriptor(CCC)
                if (descriptor == null) {
                    false
                } else {
                    val value = if (indicate) {
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    } else {
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    }
                    val submitted =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(descriptor, value) ==
                                android.bluetooth.BluetoothStatusCodes.SUCCESS
                        } else {
                            descriptor.value = value
                            gatt.writeDescriptor(descriptor)
                        }
                    submitted && acks.poll(SETUP_STEP_TIMEOUT_MS, TimeUnit.MILLISECONDS) == true
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "arming ${characteristic.uuid} failed", t)
            false
        }

        /** Read one characteristic synchronously, or null if it is absent or did not answer. */
        private fun read(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic?,
            reads: ArrayBlockingQueue<Pair<UUID, ByteArray?>>,
        ): ByteArray? {
            if (characteristic == null) return null
            return try {
                reads.clear()
                if (!gatt.readCharacteristic(characteristic)) return null
                val (uuid, value) = reads.poll(SETUP_STEP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    ?: return null
                // A completion for a different characteristic means the queue is out of step; using
                // it would decode one characteristic's bytes with another's parser.
                if (uuid != characteristic.uuid) null else value
            } catch (t: Throwable) {
                Log.w(TAG, "reading ${characteristic.uuid} failed", t)
                null
            }
        }
    }

    /** Route one notification to the field it belongs to. Called on a binder thread. */
    private fun onNotification(uuid: UUID, value: ByteArray?) {
        when (uuid) {
            TREADMILL_DATA -> {
                // A packet that fails to parse is dropped rather than allowed to blank the cache: a
                // single malformed notification should not make a live machine look unreachable. The
                // timestamp is what eventually reports a genuinely dead link.
                val parsed = FtmsCodec.parseTreadmillData(value) ?: return
                sample = parsed
                sampleAt = SystemClock.elapsedRealtime()
            }
            CONTROL_POINT -> {
                if (value != null) responses.offer(value)
            }
            STATUS -> {
                FtmsCodec.workoutStateFromStatus(value)?.let { announcedWorkoutState = it }
            }
        }
    }
}
