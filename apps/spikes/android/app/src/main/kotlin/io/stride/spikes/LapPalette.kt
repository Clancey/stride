package io.stride.spikes

/**
 * What colour the track floor is painted, and how that advances with the lap count.
 *
 * ### Why this is a file of its own
 *
 * The same reason [TrackGeometry] is. The track floor's two questions are "where is the lane" and
 * "what colour is it", and both used to be answered inside an Android `View` where
 * neither could be checked without a treadmill. Where lives in [TrackGeometry]; what colour lives
 * here. Nothing in this file imports Android, so the arithmetic that decides which lap gets which
 * colour is a JVM unit test rather than a rider squinting at a console on their fifth lap.
 *
 * ### The behaviour it exists to produce
 *
 * A lap used to end by *erasing* itself: [TrackFloorView]'s progress band grows from the start line
 * to the runner, and at the lap boundary progress wraps to zero, so the band collapsed and the plain
 * lane colour came back. Four hundred metres of work vanished in one frame.
 *
 * Instead the loop keeps what it earned. The colour lap N was filled with *becomes* the base colour
 * of the track, and lap N+1 paints the next colour over it. So for lap `L`:
 *
 * ```
 * band(L) = painted[(L - 1) mod size]   // what this lap is painting now
 * lane(L) = painted[(L - 2) mod size]   // what the lap before it left behind
 * ```
 *
 * with lap 1 the one exception: nothing has been painted yet, so its lane is [UNPAINTED]. That makes
 * lap 1 pixel-for-pixel what the track looked like before this existed — the indigo lane with the
 * teal band growing across it — and every lap after it a rotation of the same picture.
 *
 * ### Why the alphas are what they are
 *
 * `TrackFloorView.buildLaneShaders` draws lane and band as *mutually exclusive* regions, so the
 * completed part of the loop is one fill rather than band-composited-over-lane. [PAINTED_ALPHAS] is
 * therefore the alpha that composite used to produce (`1 - (1-lane)(1-band)` at each stop), which is
 * what keeps the loop from visibly thinning at the exact moment a lap rolls over — the failure this
 * whole change exists to remove, in a subtler form.
 *
 * Every entry keeps the gradient *shape* the original two had, and for the reason
 * `TrackFloorView.buildShaders` gives: translucent, brightest at the near edge, so the far side
 * sinks into whatever is playing underneath and the ring reads as ground receding away from the
 * rider rather than as plastic lying on the glass.
 */
object LapPalette {

    /**
     * One fill: a three-stop vertical gradient plus the colour of the band's leading edge.
     *
     * [far] is the top of the view — the far side of the loop — and [near] the bottom, closest to
     * the rider. Packed ARGB, because composing them here rather than through `android.graphics
     * .Color` is what keeps this file testable off-device, and because `Color.argb(float,…)` is an
     * API 26+ overload this console must never reach for.
     */
    data class Fill(val far: Int, val mid: Int, val near: Int, val edge: Int)

    /**
     * Alpha at each stop for ground the rider has already run over.
     *
     * Derived, not chosen: these are the alphas the old band-over-lane compositing produced at the
     * same three stops, so promoting a completed band to be the base colour changes the hue and
     * nothing else.
     */
    private val PAINTED_ALPHAS = intArrayOf(131, 195, 242)

    /** Alpha at each stop for lane nobody has run over yet. The original lane values. */
    private val UNPAINTED_ALPHAS = intArrayOf(76, 134, 205)

    /**
     * The lane during lap 1, before any lap has painted anything: the original indigo.
     *
     * Its edge colour is never used — an unpainted lane is not a band and is never stroked with
     * [TrackFloorView]'s band edge — but the type carries one, so it is the lane's own pale rim
     * rather than a lie about teal.
     */
    val UNPAINTED = fill(UNPAINTED_ALPHAS, 0x6C65BE, 0x8A83DC, 0xADA6F6, 0xD6D0FF)

    /**
     * The colours a completed lap can leave behind, in the order they are used.
     *
     * Teal first because it is the colour the progress band has always been, which is what makes
     * lap 1 unchanged. After that the cycle walks all the way round the cool half of Stride's
     * palette — teal, sky (Stride's Flutter theme `info`), violet, mint (`accent`) — so no two consecutive
     * laps are the same family and the wrap back to teal is still a visible change.
     *
     * Deliberately no amber and no red. The overlay already speaks in colour: amber is incline,
     * cyan is speed, and red is a machine that will not answer. A lap counter borrowing any of
     * those would be saying something about the treadmill that it does not mean.
     */
    private val painted = listOf(
        fill(PAINTED_ALPHAS, 0x40C8B8, 0x50D8C4, 0x68EED6, 0xCFFFF4), // teal — the original band
        fill(PAINTED_ALPHAS, 0x3E7CBE, 0x4F9BDF, 0x6FBEFF, 0xD5EBFF), // sky
        fill(PAINTED_ALPHAS, 0x7159B4, 0x8F72D8, 0xB692FF, 0xE4D9FF), // violet
        fill(PAINTED_ALPHAS, 0x269C6B, 0x34C489, 0x46E58F, 0xD2FFE3), // mint
    )

    /** How many laps the rider runs before the colours repeat. */
    val size: Int get() = painted.size

    /** The colour lap [lap] is painting over the loop right now. */
    fun band(lap: Int): Fill = painted[bandIndex(lap)]

    /**
     * The colour the loop already is when lap [lap] starts.
     *
     * Lap 1 (and anything before it) has nothing behind it, so it gets [UNPAINTED].
     */
    fun lane(lap: Int): Fill = if (lap <= 1) UNPAINTED else painted[laneIndex(lap)]

    /**
     * `(lap - 1) mod size`, reduced before the subtraction.
     *
     * The order matters for exactly one input. Writing it as `floorMod(lap - 1, size)` overflows on
     * `Int.MIN_VALUE` and returns a negative index; reducing first cannot, so no reading — however
     * corrupt — can index off the end of the palette and take the overlay down with it.
     */
    internal fun bandIndex(lap: Int): Int = floorMod(floorMod(lap, size) - 1, size)

    /** `(lap - 2) mod size`: the band index of the lap before this one. */
    internal fun laneIndex(lap: Int): Int = floorMod(bandIndex(lap) - 1, size)

    private fun fill(alphas: IntArray, far: Int, mid: Int, near: Int, edge: Int): Fill = Fill(
        far = argb(alphas[0], far),
        mid = argb(alphas[1], mid),
        near = argb(alphas[2], near),
        // Opaque. TrackFloorView re-asserts every paint's alpha from its own design table on each
        // frame, so an alpha packed in here would be overwritten anyway; carrying 0xFF says that.
        edge = argb(255, edge),
    )

    private fun argb(alpha: Int, rgb: Int): Int = (alpha shl 24) or (rgb and 0xFFFFFF)

    private fun floorMod(value: Int, mod: Int): Int {
        val r = value % mod
        return if (r < 0) r + mod else r
    }
}
