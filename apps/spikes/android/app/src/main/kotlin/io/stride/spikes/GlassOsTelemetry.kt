package io.stride.spikes

/**
 * How to read a GlassOS metric without accidentally lying about it.
 *
 * This exists because the console's API hands us the fabricated-zero bug by default, and the bug it
 * hands us is the exact one this project already shipped once and deliberately removed: a confident
 * `0.0` next to a belt whose real state we do not know, which reads as "the belt is stopped".
 *
 * ## The problem, measured rather than theorised
 *
 * proto3 does not serialise default values. A numeric field that is genuinely `0` and a field that
 * was never set are byte-identical on the wire, so a naive parse into non-nullable numerics turns
 * both into `0.0`. Captured live from the user's Commercial 1750 on 2026-08-13:
 *
 * ```
 * // console IDLE, no workout at all
 * SpeedService/GetSpeed        -> {}
 * DistanceService/GetDistance  -> { "remainingDistanceKm": "NaN" }
 *
 * // belt actually moving at 1.609 kph, incline actually 0%
 * InclineSubscription -> { "workoutID": "d3a3a6d8-..." }
 * SpeedSubscription   -> { "workoutID": "d3a3a6d8-...", "timeSeconds": 36, "lastKph": 1.6093 }
 * ```
 *
 * Look at the incline message. Incline was a real, known, measured `0%` — and the entire numeric
 * payload is absent. It is indistinguishable, field-for-field, from the IDLE case where we know
 * nothing. That is why "absent means zero" and "absent means unknown" are *both* wrong as blanket
 * rules, and why this cannot be left to whoever writes the parser.
 *
 * ## The rule that disambiguates it
 *
 * The workout identity is the discriminator. GlassOS stamps `workoutID` on every metric message
 * belonging to a live workout, and omits it when there is no workout context. Combined with
 * `CanRead`, that is enough:
 *
 * | `CanRead` | `workoutID` | numeric field | means |
 * |---|---|---|---|
 * | false | any | any | **unknown** — service unavailable on this machine |
 * | true | absent | absent | **unknown** — no workout context, nothing is being measured |
 * | true | present | absent | **genuine zero** — measured, and the value really is 0 |
 * | true | present | present | the value |
 * | any | any | `NaN` | **unknown** — GlassOS uses NaN for "no figure", seen on `remainingDistanceKm` |
 *
 * ## Why `CanRead` must default to false
 *
 * `AvailabilityResponse { bool isAvailable }` is proto3 too, so a service that is *not* available
 * answers `{}` — the `false` is omitted, not transmitted. A client that defaults that field to
 * `true` therefore concludes the exact opposite of what the machine said. Measured on the 1750:
 * Distance, Speed, Incline, ElapsedTime, CaloriesBurned and HeartRate answer
 * `{"isAvailable": true}`; StepCount and Cadence answer `{}`, i.e. unavailable — which is correct,
 * the 1750 has no cadence sensor. Deny by default.
 */
object GlassOsTelemetry {

    /**
     * Interpret one numeric metric field into a reading that is honest about its own absence.
     *
     * Returns null for "we do not know", which callers must render as [MachineLink.NO_READING] and
     * must never render as a number. Returns `0.0` only when the machine genuinely measured zero.
     *
     * @param raw the field as decoded, or null if the field was absent from the message
     * @param workoutId the `workoutID` stamped on the message, or null if absent
     * @param canRead the *explicit* result of the service's `CanRead` call; see the class note on
     *   why this must be resolved with `false` as the default rather than `true`
     */
    fun reading(raw: Double?, workoutId: String?, canRead: Boolean): Double? {
        if (!canRead) return null
        // NaN is GlassOS's own "no figure" marker and appears in normal operation. It must be
        // caught explicitly: NaN fails every comparison, so a range check like `v < 0` silently
        // lets it through and it would reach the UI as the string "NaN".
        if (raw != null && raw.isNaN()) return null
        val inWorkout = !workoutId.isNullOrEmpty()
        return when {
            raw != null -> raw
            // Absent inside a live workout is a real zero: the machine is measuring, and proto3
            // dropped the value precisely *because* it was zero.
            inWorkout -> 0.0
            // Absent with no workout context means nothing is being measured. Not zero.
            else -> null
        }
    }

    /**
     * Resolve an `AvailabilityResponse.isAvailable` field. Absent means false, never true.
     *
     * Written as a named function rather than an inline `?: false` so the decision is greppable and
     * so nobody "tidies" it into a truthier default later.
     */
    fun availability(isAvailableField: Boolean?): Boolean = isAvailableField ?: false

    /** Kilometres per hour as reported by GlassOS, to the miles per hour Stride displays. */
    fun kphToMph(kph: Double?): Double? = kph?.let { it * 0.621371192 }

    /** Kilometres as reported by GlassOS, to the miles Stride displays. */
    fun kmToMiles(km: Double?): Double? = km?.let { it * 0.621371192 }

    /**
     * Pace in minutes per mile, derived from a *measured* speed only.
     *
     * Deliberately takes speed rather than distance and time. Deriving pace from elapsed time
     * against an assumed speed is the specific dishonesty this project forbids, and taking speed as
     * the input makes that mistake impossible to write here.
     *
     * Returns null below walking speed rather than a huge number: GlassOS reports a `minKph` of
     * 1.608 (1.0 mph) on this machine, so anything at or near zero is a stopped belt, and
     * "482 min/mi" is noise dressed up as information.
     */
    fun paceMinPerMile(speedMph: Double?): Double? {
        if (speedMph == null || speedMph.isNaN() || speedMph < 0.1) return null
        return 60.0 / speedMph
    }
}
