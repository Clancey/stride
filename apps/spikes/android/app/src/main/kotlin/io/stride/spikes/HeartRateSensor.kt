package io.stride.spikes

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A live link to one BLE heart rate strap.
 *
 * ## Deliberately orthogonal to the machine transport
 *
 * This is not a [MachineCommands] implementation and is not selected by
 * [StrideSettings.Transport]. A rider on GlassOS, on the direct register path or on FTMS should all
 * be able to wear a strap, so binding heart rate to the transport chooser would force a choice
 * between the two that nothing about the hardware requires. [MachineLink] owns one of these
 * alongside whichever machine transport is open, and closes them independently.
 *
 * ## Read-only, by construction
 *
 * There is no write path here at all: no control point, no command method, nothing that takes a
 * value. A heart rate strap has nothing to command, so the class that talks to one should have no
 * way to try. That makes "this cannot move a belt" a structural fact rather than a promise.
 *
 * Like [FtmsTransport] this is a cache with a timestamp rather than a request/response link, because
 * a strap notifies on its own schedule — roughly once a second — and nothing asks it to.
 */
@SuppressLint("MissingPermission")
class HeartRateSensor private constructor(
    private val gatt: BluetoothGatt,
    val deviceName: String,
) : Closeable {

    @Volatile private var closed = false

    /** Set by the callback when the link drops, as distinct from us closing it. */
    @Volatile private var dropped = false

    val connected: Boolean get() = !closed && !dropped

    @Volatile private var measurement: HeartRateCodec.Measurement? = null
    @Volatile private var measurementAt: Long = 0L

    /** Where the strap says it is worn, if it says. Informational only. */
    @Volatile var bodyLocation: String? = null
        private set

    /** Strap battery percentage, if it publishes one. */
    @Volatile var batteryPercent: Int? = null
        private set

    /**
     * The latest measurement and the monotonic time it arrived, or null if none has.
     *
     * The caller decides what counts as too old; this does not, because freshness is a display
     * decision and belongs with the rest of them.
     */
    fun latest(): Pair<HeartRateCodec.Measurement, Long>? =
        measurement?.let { it to measurementAt }

    override fun close() {
        closed = true
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }

    private fun onNotification(uuid: UUID, value: ByteArray?) {
        if (uuid != MEASUREMENT) return
        // A packet that fails to parse — including a zero bpm from a strap still searching for a
        // signal — is dropped rather than allowed to blank the cache. One bad notification should
        // not make a working strap look absent; the timestamp is what reports a genuinely dead one.
        val parsed = HeartRateCodec.parseMeasurement(value) ?: return
        measurement = parsed
        measurementAt = SystemClock.elapsedRealtime()
    }

    companion object {
        const val TAG = "HeartRateSensor"

        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val SETUP_STEP_TIMEOUT_MS = 3_000L

        /**
         * How old a reading may be before it stops being a reading.
         *
         * Straps notify at about 1 Hz, so six seconds is several missed beats rather than one late
         * one. Past that the honest answer is [MachineLink.NO_READING]: a heart rate frozen at the
         * last value the strap managed to send looks exactly like a heart rate that is still true,
         * and a rider reading 150 from a strap that fell off ten minutes ago is being misinformed
         * about their own body.
         */
        const val READING_TTL_MS = 6_000L

        private val SERVICE = FtmsTransport.uuid(HeartRateCodec.Uuid.SERVICE)
        private val MEASUREMENT = FtmsTransport.uuid(HeartRateCodec.Uuid.MEASUREMENT)
        private val BODY_LOCATION = FtmsTransport.uuid(HeartRateCodec.Uuid.BODY_SENSOR_LOCATION)
        private val BATTERY_SERVICE = FtmsTransport.uuid(HeartRateCodec.Uuid.BATTERY_SERVICE)
        private val BATTERY_LEVEL = FtmsTransport.uuid(HeartRateCodec.Uuid.BATTERY_LEVEL)
        private val CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * Connect to a bonded heart rate strap, or null if there is not one to reach.
         *
         * **Bonded devices only, and no scanning at all** — a deliberately narrower rule than
         * [FtmsTransport]'s. Straps advertise constantly and in a gym there may be dozens in range,
         * so scanning would mean either connecting to a stranger's or asking the rider to pick from
         * a list of MAC addresses. Pairing once in Android settings names the one they own, and
         * costs no location permission on any API level.
         *
         * Blocking, and deliberately so: callers run it on a background thread and an asynchronous
         * open would push connection state into every one of them.
         */
        fun open(context: Context): HeartRateSensor? {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                ?: return null
            val adapter: BluetoothAdapter = manager.adapter ?: return null
            if (!adapter.isEnabled) return null

            val bonded = try {
                adapter.bondedDevices ?: emptySet()
            } catch (t: SecurityException) {
                Log.w(TAG, "no bluetooth permission to list bonded devices", t)
                return null
            }

            for (device in bonded) {
                val sensor = connect(context, device)
                if (sensor != null) return sensor
            }
            return null
        }

        private fun connect(context: Context, device: BluetoothDevice): HeartRateSensor? {
            val discovered = CountDownLatch(1)
            val descriptorWrites = ArrayBlockingQueue<Boolean>(1)
            val reads = ArrayBlockingQueue<Pair<UUID, ByteArray?>>(1)
            var services: BluetoothGatt? = null
            // AtomicReference rather than a captured local: this is written on the connect
            // thread and read on Binder callback threads, and a plain local carries no
            // happens-before edge between them. A callback that observed null would drop a
            // write acknowledgement, a notification, or -- worst -- the single disconnect
            // event, leaving `connected` true forever on a dead link that is never reopened.
            val pending = java.util.concurrent.atomic.AtomicReference<HeartRateSensor?>(null)

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
                        pending.get()?.dropped = true
                        discovered.countDown()
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) services = g
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
                    val v = if (status == BluetoothGatt.GATT_SUCCESS) characteristic.value else null
                    reads.offer(characteristic.uuid to v)
                }

                override fun onCharacteristicRead(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                    status: Int,
                ) {
                    reads.offer(
                        characteristic.uuid to value.takeIf { status == BluetoothGatt.GATT_SUCCESS },
                    )
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
                    pending.get()?.onNotification(characteristic.uuid, characteristic.value)
                }

                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    pending.get()?.onNotification(characteristic.uuid, value)
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

            val hr = services?.getService(SERVICE)
            val measurement = hr?.getCharacteristic(MEASUREMENT)
            if (measurement == null) {
                // Not a heart rate strap. Every bonded device gets looked at, and one that does not
                // expose this service is simply something else the rider has paired.
                runCatching { gatt.close() }
                return null
            }

            val name = try {
                device.name ?: device.address ?: "strap"
            } catch (t: SecurityException) {
                "strap"
            }
            val sensor = HeartRateSensor(gatt, name)
            pending.set(sensor)

            if (!arm(gatt, measurement, descriptorWrites)) {
                Log.w(TAG, "could not arm heart rate notifications")
                sensor.close()
                return null
            }

            // Both optional and both purely informational, so neither failing is a reason to give up
            // a working heart rate.
            sensor.bodyLocation = HeartRateCodec.bodySensorLocation(
                read(gatt, hr.getCharacteristic(BODY_LOCATION), reads),
            )
            sensor.batteryPercent = HeartRateCodec.batteryPercent(
                read(
                    gatt,
                    services?.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_LEVEL),
                    reads,
                ),
            )

            return sensor
        }

        @Suppress("DEPRECATION")
        private fun arm(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            acks: ArrayBlockingQueue<Boolean>,
        ): Boolean = try {
            acks.clear()
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                false
            } else {
                val descriptor = characteristic.getDescriptor(CCC)
                if (descriptor == null) {
                    false
                } else {
                    val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
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
                if (uuid != characteristic.uuid) null else value
            } catch (t: Throwable) {
                Log.w(TAG, "reading ${characteristic.uuid} failed", t)
                null
            }
        }
    }
}

/**
 * Where a heart rate reading came from.
 *
 * Worth naming rather than leaving implicit, because the two sources are not equally trustworthy and
 * the rider can act on the difference: a machine-reported rate comes from hand grips they are
 * probably not holding, and its absence usually means "let go", not "no pulse".
 */
enum class HeartRateSource {
    /** A dedicated BLE chest strap. Continuous and accurate. */
    STRAP,

    /** The machine's own sensor, carried in FTMS telemetry. Usually hand grips. */
    MACHINE,
    ;

    val label: String
        get() = when (this) {
            STRAP -> "strap"
            MACHINE -> "machine"
        }
}
