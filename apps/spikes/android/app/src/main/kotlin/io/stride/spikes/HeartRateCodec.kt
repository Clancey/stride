package io.stride.spikes

/**
 * Pure codec for the Bluetooth SIG **Heart Rate Service** (`0x180D`).
 *
 * ## Why a chest strap earns its own driver
 *
 * Heart rate is the one metric on this console that the *machine* is worst at. A treadmill measures
 * it through hand grips a runner is not holding, so it reads nothing for most of a workout and reads
 * badly for the rest. A chest strap measures it continuously and accurately, and every strap ever
 * made speaks this profile — it is older, simpler and far more universally implemented than FTMS.
 *
 * `PLAN.md` §1 lists live heart rate as a goal in its own right. This is the cheapest possible way
 * to deliver it: one characteristic, one notification, no control surface and nothing that can move
 * a belt.
 *
 * ## A strap is a sensor, not a machine
 *
 * Deliberately **not** a [MachineCommands] implementation. That interface exists for things that can
 * be commanded, and every method on it — `setSpeedKph`, `startWorkout`, `stop` — is meaningless for a
 * strap. Forcing one through it would mean a dozen methods answering "not supported" and, worse,
 * would put a heart rate belt behind the transport selector, so choosing a strap would mean giving
 * up machine control. They are orthogonal: a rider on GlassOS should be able to wear a strap.
 *
 * This is `PLAN.md` §3.4's `MetricSource` arriving ahead of the rest of that abstraction, which is
 * the right order — it is the second kind of thing that produces a metric, and until now there was
 * only one.
 *
 * ## The layout
 *
 * Like FTMS, the payload is flag-driven and variable-length, so the same discipline applies: a
 * misread flag yields a plausible wrong number rather than an error. Unlike FTMS the flags are a
 * single byte, and **bit 0 changes the width of the very next field**, which is the one thing here
 * that must not be got wrong.
 */
object HeartRateCodec {

    /** SIG assigned numbers for the service and the characteristics worth reading. */
    object Uuid {
        /** Heart Rate Service. */
        const val SERVICE = 0x180D

        /** Heart Rate Measurement — the notification carrying bpm. */
        const val MEASUREMENT = 0x2A37

        /** Body Sensor Location. Read once; purely informational. */
        const val BODY_SENSOR_LOCATION = 0x2A38

        /** Battery level, from the separate Battery Service. */
        const val BATTERY_SERVICE = 0x180F
        const val BATTERY_LEVEL = 0x2A19
    }

    private object Flag {
        /** 0 = the value is one byte, 1 = two bytes. The only flag that changes a field's width. */
        const val VALUE_IS_UINT16 = 1 shl 0
        const val SENSOR_CONTACT_DETECTED = 1 shl 1
        const val SENSOR_CONTACT_SUPPORTED = 1 shl 2
        const val ENERGY_EXPENDED = 1 shl 3
        const val RR_INTERVALS = 1 shl 4
    }

    /**
     * One Heart Rate Measurement notification, decoded.
     *
     * Every field is nullable and null means the strap did not send it — never zero. A fabricated
     * `0` bpm is the same class of lie as a fabricated `0.0` speed: it reads as a fact about the
     * rider rather than as an absence of information.
     */
    data class Measurement(
        val bpm: Int,
        /**
         * Whether the strap is in contact with skin.
         *
         * Three states, and they are all different. `true` means contact is detected, `false` means
         * the strap says it is **not** in contact — the reading is unreliable and probably stale —
         * and null means the strap does not report contact at all, which most cheap ones do not.
         * Collapsing null into `false` would mark every reading from those straps as untrustworthy.
         */
        val sensorContact: Boolean? = null,
        /**
         * Energy expended in **kilojoules**, which is what the Heart Rate Service defines — not
         * kilocalories. Named for the unit on the wire so nobody adds it to the calorie readout,
         * which is in kcal and would then be roughly four times too large.
         */
        val energyExpendedKj: Int? = null,
        /** Beat-to-beat intervals in milliseconds, oldest first. Empty when not reported. */
        val rrIntervalsMs: List<Double> = emptyList(),
    )

    /**
     * Decode a Heart Rate Measurement notification, or null if it is not usable.
     *
     * Returns null for a payload too short to hold the flags and a value, and for a **zero** bpm.
     * Zero is not a heart rate: straps emit it while searching for a signal, at startup, and when
     * the belt is off. Publishing it would draw a confident `0` next to a running rider, which is
     * both alarming and false. Absent is the honest reading, and `MachineLink.NO_READING` already
     * knows how to draw it.
     */
    fun parseMeasurement(bytes: ByteArray?): Measurement? {
        if (bytes == null || bytes.size < 2) return null
        val flags = u8(bytes, 0)
        var at = 1

        fun room(n: Int): Boolean = at + n <= bytes.size

        val bpm: Int
        if (flags and Flag.VALUE_IS_UINT16 != 0) {
            // Two-byte value. Straps use this above 255 bpm, which no human reaches — but some
            // report it always, so the width must follow the flag rather than the plausibility of
            // the number.
            if (!room(2)) return null
            bpm = u16(bytes, at)
            at += 2
        } else {
            bpm = u8(bytes, at)
            at += 1
        }
        if (bpm <= 0) return null

        // Contact is only meaningful when the strap says it supports reporting it. A strap that does
        // not will leave both bits clear, which must read as "does not say" rather than "not
        // touching skin".
        val contact = if (flags and Flag.SENSOR_CONTACT_SUPPORTED != 0) {
            flags and Flag.SENSOR_CONTACT_DETECTED != 0
        } else {
            null
        }

        var energyKj: Int? = null
        if (flags and Flag.ENERGY_EXPENDED != 0) {
            if (!room(2)) return null
            energyKj = u16(bytes, at)
            at += 2
        }

        val rr = mutableListOf<Double>()
        if (flags and Flag.RR_INTERVALS != 0) {
            // The count is not transmitted: the field repeats to the end of the packet, so the
            // remaining length is the count. It must therefore be a whole number of two-byte
            // intervals and at least one -- anything else means this packet is not what its own
            // flags claim, and the safe reading of a malformed heart rate packet is to discard it
            // rather than to trust the part that happened to parse.
            val remaining = bytes.size - at
            if (remaining < 2 || remaining % 2 != 0) return null
            while (at + 2 <= bytes.size) {
                // Carried in 1/1024 second units, not milliseconds.
                rr += u16(bytes, at) * 1000.0 / 1024.0
                at += 2
            }
        }

        return Measurement(
            bpm = bpm,
            sensorContact = contact,
            energyExpendedKj = energyKj,
            rrIntervalsMs = rr,
        )
    }

    /**
     * Decode Body Sensor Location (`0x2A38`) into something worth showing a rider.
     *
     * Null for an unrecognised code rather than a number, because "location 7" tells a rider nothing
     * they could act on.
     */
    fun bodySensorLocation(bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        return when (u8(bytes, 0)) {
            0 -> "other"
            1 -> "chest"
            2 -> "wrist"
            3 -> "finger"
            4 -> "hand"
            5 -> "earlobe"
            6 -> "foot"
            else -> null
        }
    }

    /** Battery Level (`0x2A19`), a percentage, or null when out of range or absent. */
    fun batteryPercent(bytes: ByteArray?): Int? {
        if (bytes == null || bytes.isEmpty()) return null
        return u8(bytes, 0).takeIf { it in 0..100 }
    }

    private fun u8(b: ByteArray, at: Int): Int = b[at].toInt() and 0xFF

    private fun u16(b: ByteArray, at: Int): Int = u8(b, at) or (u8(b, at + 1) shl 8)
}
